package experiments;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichBolt;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.topology.base.BaseRichSpout;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import org.apache.flink.api.java.utils.ParameterTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class ThroughputHostsTracking {

	public static Logger LOG = LoggerFactory.getLogger(ThroughputHostsTracking.class);

	public static Fields FIELDS = new Fields("id", "host", "time", "payload", "hosts");

	public static class Generator extends BaseRichSpout {

		private final int delay;
		private final boolean withFt;
		private final int latFreq;
		private SpoutOutputCollector spoutOutputCollector;
		private long id = 0;
		private byte[] payload;
		private long time = 0;
		private int nextlat = 1000;
		private int sleepFreq;
		private String host;

		public Generator(ParameterTool pt) {
			this.payload = new byte[pt.getInt("payload")];
			this.delay = pt.getInt("delay");
			this.withFt = pt.has("ft");
			this.latFreq = pt.getInt("latencyFreq");
			this.sleepFreq = pt.getInt("sleepFreq");
		}

		@Override
		public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
			outputFieldsDeclarer.declare(FIELDS);
		}

		@Override
		public void open(Map map, TopologyContext topologyContext, SpoutOutputCollector spoutOutputCollector) {
			this.spoutOutputCollector = spoutOutputCollector;
			try {
				this.host = InetAddress.getLocalHost().getHostName();
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		}

		@Override
		public void nextTuple() {
			if(delay > 0) {
				if(id % sleepFreq == 0) {
					try { Thread.sleep(delay); } catch (InterruptedException e) { e.printStackTrace();}
				}
			}
			if(id % latFreq == nextlat) {
				time = System.currentTimeMillis();
				if(--nextlat <= 0) {
					nextlat = 1000;
				}
			}
			ArrayList<String> hosts = new ArrayList<String>();
			hosts.add(host);

			if(withFt) {
				spoutOutputCollector.emit(new Values(this.id, this.host, this.time, this.payload, hosts), this.id);
			} else {
				spoutOutputCollector.emit(new Values(this.id, this.host, this.time, this.payload, hosts));
			}

			time = 0;
			this.id++;
		}

		@Override
		public void ack(Object msgId) {
		// 	LOG.info("Acked msg "+msgId);
		}

		/**
		 * Resend failed tuple
		 *
		 */
		@Override
		public void fail(Object msgId) {
			long id = (Long)msgId;
			ArrayList<String> hosts = new ArrayList<String>();
			hosts.add(host+"-after-failure");
			LOG.info("Failed message " + msgId);
			spoutOutputCollector.emit(new Values(id, this.host, 0L, this.payload, hosts), id);
		}
	}

	public static class RepartPassThroughBolt implements IRichBolt {

		private final boolean withFt;
		private OutputCollector collector;

		private String host;

		public RepartPassThroughBolt(ParameterTool pt) {
			this.withFt = pt.has("ft");
		}
		@Override
		public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
			this.collector = collector;
			try {
				this.host = InetAddress.getLocalHost().getHostName();
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		}

		@Override
		public void execute(Tuple input) {
			if(withFt) {
				// anchor the output on the only input element (we pass through)
				List<Object> vals = input.getValues();
				Long v = (Long) vals.get(0);
				v++;
				vals.set(0, v);

				((List<String>)vals.get(4)).add(host);

				collector.emit(input, vals);
				// acknowledge the element upstream.
				collector.ack(input);
			} else {
				// unanchored pass forward
				collector.emit(input.getValues());
			}
		}

		@Override
		public void cleanup() {

		}

		@Override
		public void declareOutputFields(OutputFieldsDeclarer declarer) {
			declarer.declare(FIELDS);
		}

		@Override
		public Map<String, Object> getComponentConfiguration() {
			return null;
		}
	}
	public static class Sink implements IRichBolt {

		private final boolean withFT;
		private final String host;
		long received = 0;
		long start = 0;
		ParameterTool pt;
		private OutputCollector collector;
		private long logfreq;
		private long lastLog = -1;
		private long lastElements;

		public Sink(ParameterTool pt) throws UnknownHostException {
			this.pt = pt;
			this.withFT = pt.has("ft");
			this.logfreq = pt.getInt("logfreq");
			this.host = InetAddress.getLocalHost().getHostName();
		}

		@Override
		public void execute(Tuple input) {
			if(start == 0) {
				start = System.currentTimeMillis();
			}
			received++;
			if(received % logfreq == 0) {
				long now = System.currentTimeMillis();

				/*long sinceSec = ((now - start)/1000);
				if(sinceSec == 0) return;
				LOG.info("Received {} elements since {}. Elements per second {}, GB received {}",
						received,
						sinceSec,
						received/sinceSec ,
						(received * (8 + 8 + 4 + pt.getInt("payload")))/1024/1024/1024 ); */

				// throughput for the last "logfreq" elements
				if(lastLog == -1) {
					// init (the first)
					lastLog = now;
					lastElements = received;
				} else {
					long timeDiff = now - lastLog;
					long elementDiff = received - lastElements;
					double ex = (1000/(double)timeDiff);
					LOG.info("During the last {} ms, we received {} elements. That's {} elements/second/core", timeDiff, elementDiff, elementDiff*ex);
					// reinit
					lastLog = now;
					lastElements = received;
				}
			}

			if(input.getLong(2) != 0 && input.getString(1).equals(host)) {
				long lat = System.currentTimeMillis() - input.getLong(2);
				((List<String>)input.getValues().get(4)).add(host);
				LOG.info("Latency {} ms from machine "+host+" after hosts "+input.getValues().get(4), lat);
			}

			if(withFT) {
				collector.ack(input);
			}
		}

		@Override
		public void declareOutputFields(OutputFieldsDeclarer declarer) {
		}

		@Override
		public Map<String, Object> getComponentConfiguration() {
			return null;
		}

		@Override
		public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
			this.collector = collector;
		}

		@Override
		public void cleanup() {

		}
	}



	public static void main(String[] args) throws Exception {
		ParameterTool pt = ParameterTool.fromArgs(args);

		int par = pt.getInt("para");

		TopologyBuilder builder = new TopologyBuilder();

		builder.setSpout("source0", new Generator(pt), pt.getInt("sourceParallelism"));
		int i = 0;
		for(; i < pt.getInt("repartitions", 1) - 1;i++) {
			System.out.println("adding source"+i+" --> source"+(i+1));
			builder.setBolt("source"+(i+1), new RepartPassThroughBolt(pt), pt.getInt("sinkParallelism")).fieldsGrouping("source" + i, new Fields("id"));
		}
		System.out.println("adding final source"+i+" --> sink");

		builder.setBolt("sink", new Sink(pt), pt.getInt("sinkParallelism")).fieldsGrouping("source"+i, new Fields("id"));


		Config conf = new Config();
		conf.setDebug(false);
		//System.exit(1);

		if (!pt.has("local")) {
			conf.setNumWorkers(par);

			StormSubmitter.submitTopologyWithProgressBar("throughput-"+pt.get("name", "no_name"), conf, builder.createTopology());
		}
		else {
			conf.setMaxTaskParallelism(par);

			LocalCluster cluster = new LocalCluster();
			cluster.submitTopology("throughput", conf, builder.createTopology());

			Thread.sleep(300000);

			cluster.shutdown();
		}

	}
}
