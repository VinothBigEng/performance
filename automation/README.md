Automatically execute a bunch of tests for Flink in a distribted setting
####

# Preparation

Understand that I'm following a requirements-based development process.
It is likely that a special corner case is not covered, our that my use case is actually a corner case.
Anyways, please let me know if something is missing for you or make a pull request.

# Required applications
- bash
- git
- maven 3

for wordcount data:
- aspell
- ruby
- iconv

for tpch:
- make
- a c compiler (gcc)

for performance evaluation
- python

```
sudo apt-get install git maven aspell ruby make python
```

# Execution Order 

```
cp configDefaults.sh config.sh
#set values here
cd flink-conf
# edit the configuration files for Flink and remove the '.template' extension
cd ..
nano config.sh
./prepareFlink.sh
./prepareTestjob.sh
./generateWCdata.sh
./generateCPdata.sh
./generateTestjobdata.sh
./uploadToHdfs.sh
./startFlink.sh
./runWC.sh
./runCP.sh
./runTestjob.sh

# now you can run a job
./runWC.sh
```

# Performance Evaluation

After setting up everything, simply run ```./performance.sh``` with default message or use ```./performance.sh -m "did something"``` to give the comment.
