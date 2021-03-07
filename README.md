# Aerospike Microbenchmark: Sliding Window - Time
This example shows how to keep 30ms worth of values in a single sliding window by multiple threads.

Creates multi-threaded workers that will:

* for best performance, data is stored in memory
* create a sliding window bin holding a map timestamp as key associated with a list of transaction ids
* for a specific timestamp (key) put a transaction into a list
* check that the transaction exists in the list
* Compare with doing your own using custom: Maps, Lists and JSON

## Dependencies
* Maven
* Java 8
* Aerospike Client 4.4.6
* Aerospike Server CE 4.8.0.6
* Docker Compose

## Performance metrics
Check on your own system (adjust the number of threads):
- Ryzen 9 3950X (32 logical cores) on Windows 10 using Docker Desktop single instance of Aerospike server:
    - RECORD_CHECK_ENABLED=false - atomic operations: 16K operations per second
    - RECORD_CHECK_ENABLED=true - atomic operations with post record check: 8K operations per second
    - RECORD_CHECK_ENABLED=false 
       - atomic operations using CDT: 16K operations per second
       - atomic operations using custom: 833 

## Usage
```
docker-compose up -d
mvn install
```

## Data Model
To examine the structure of the bin perform this AQL command:
```
aql> select * from test
+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| mapbin1                                                                                                                                                                                                                                                        |
+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| KEY_ORDERED_MAP('{1614945124800:["f62a8dc4-64a2-40eb-bcc4-648f7521c3f0"], 1614945124801:["c2e490c8-7d6c-489d-ad1a-7f4a2e62a136"], 1614945124803:["7c234fc3-5ec9-4565-9ccb-b69bd0a21b44"], 1614945124804:["64956264-2d68-4c9a-b354-c52eff7242d3"], 161494512477 |
+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
```