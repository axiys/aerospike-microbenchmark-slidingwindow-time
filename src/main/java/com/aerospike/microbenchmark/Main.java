package com.aerospike.microbenchmark;

import com.aerospike.client.*;
import com.aerospike.client.Record;
import com.aerospike.client.cdt.*;
import com.aerospike.client.policy.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {

    private static boolean RECORD_CHECK_ENABLED = false;

    public enum TestMode {
        BinMap,
        Json,
        Blob
    }

    private static TestMode TEST_MODE = TestMode.BinMap;

    private static int NUMBER_OF_THREADS = 2 * 16; /// Use: lscpu for number of CPUs eg. 16
    private static int NUMBER_OF_OPERATIONS_PER_THREAD = 100000;

    private static Random random = new Random(LocalDateTime.now().getNano() * LocalDateTime.now().getSecond());

    public static void main(String[] args) {

        Instant startTime = Instant.now();

        ExecutorService es = Executors.newCachedThreadPool();
        int n = NUMBER_OF_THREADS;
        while (n-- > 0) {
            es.execute(Main::runWorker);
        }
        es.shutdown();
        try {
            boolean finished = es.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        Instant endTime = Instant.now();
        long deltaTime_ms = endTime.toEpochMilli() - startTime.toEpochMilli();
        long rate = (NUMBER_OF_THREADS * NUMBER_OF_OPERATIONS_PER_THREAD) / Math.round(deltaTime_ms / 1000.0);
        System.out.println("ops/sec (approx): " + rate);
    }

    private static boolean runWorker() {
        // Set client default policies
        ClientPolicy clientPolicy = new ClientPolicy();
        clientPolicy.readPolicyDefault.replica = Replica.MASTER;
        clientPolicy.readPolicyDefault.socketTimeout = 100;
        clientPolicy.readPolicyDefault.totalTimeout = 100;
        clientPolicy.writePolicyDefault.commitLevel = CommitLevel.COMMIT_ALL;
        clientPolicy.writePolicyDefault.socketTimeout = 500;
        clientPolicy.writePolicyDefault.totalTimeout = 500;
        clientPolicy.writePolicyDefault.expiration = -1;

        // Connect to the cluster.
        AerospikeClient client = new AerospikeClient(clientPolicy, new Host("127.0.0.1", 3000));

        try {

            MapPolicy mapPolicy = new MapPolicy(MapOrder.KEY_ORDERED, MapWriteFlags.CREATE_ONLY | MapWriteFlags.NO_FAIL);
            ListPolicy listPolicy = new ListPolicy(ListOrder.UNORDERED, ListWriteFlags.ADD_UNIQUE | ListWriteFlags.NO_FAIL);

            Key key = new Key("test", "demo", "mapkey_" + UUID.randomUUID());
            String mapBinName = "mapbin1";

            ObjectMapper mapper = new ObjectMapper();

            int n = NUMBER_OF_OPERATIONS_PER_THREAD;
            while (n-- > 0) {
                long transactionTimestamp = Instant.now().toEpochMilli();
                String transactionId = UUID.randomUUID().toString();

                // Keep only certain timeframe worth of values
                long transactionTimestampLowWatermark = transactionTimestamp - 30;

                boolean retry = false;

                do {

                    if (TEST_MODE == TestMode.BinMap) {
                        // This is the default list of transactions (empty) per timestamp (key)
                        List<Value> transactions = new ArrayList<>();
                        Map<Value, Value> initMap = new HashMap<>();
                        initMap.put(Value.get(transactionTimestamp), Value.get(transactions));

                        Operation[] operations = new Operation[]{
                                // If the timestamp (key) doesn't exist yet, then create it
                                // - This is controlled by a Map Policy to ensure create only
                                MapOperation.putItems(mapPolicy, mapBinName, initMap),

                                // Append the transaction id to the list specified timestamp (key)
                                // RATIONALE: other threads may be adding to this timestamp's entry at the same time
                                // - This is controlled by a List Policy to ensure no duplicates
                                ListOperation.append(listPolicy, mapBinName, Value.get(transactionId), CTX.mapKey(Value.get(transactionTimestamp))),

                                // Remove old items - use a low water mark timestamp
                                // - Remove timestamps (keys) which are out of range now
                                MapOperation.removeByKeyRange(mapBinName, null, Value.get(transactionTimestampLowWatermark), MapReturnType.KEY)
                        };

                        // Repeat operation on hot key
                        retry = false;
                        try {
                            client.operate(null, key, operations);
                        } catch (AerospikeException ex) {
                            if (ex.getResultCode() == ResultCode.KEY_BUSY) {
                                retry = true;
                            } else {
                                throw new Exception(String.format(
                                        "Unexpected set return code: namespace=%s set=%s key=%s bin=%s code=%s",
                                        key.namespace, key.setName, key.userKey, mapBinName, ex.getResultCode()));
                            }
                        }
                    } else {
                        Record recordToUpdate = client.get(new Policy(), key, mapBinName);

                        HashMap<Long, ArrayList<String>> map;

                        WritePolicy writePolicy = new WritePolicy();
                        writePolicy.commitLevel = CommitLevel.COMMIT_ALL;
                        writePolicy.expiration = -1;

                        if (recordToUpdate == null || !recordToUpdate.bins.containsKey(mapBinName)) {
                            map = new HashMap<>();
                        } else {
                            if (TEST_MODE == TestMode.Json) {
                                String json = recordToUpdate.getString(mapBinName);
                                TypeReference<HashMap<Long, ArrayList<String>>> typeRef = new TypeReference<HashMap<Long, ArrayList<String>>>() {
                                };
                                map = mapper.readValue(json, typeRef);
                            } else if (TEST_MODE == TestMode.Blob) {
                                byte[] blob = (byte[]) recordToUpdate.getValue(mapBinName);
                                ByteArrayInputStream bis = null;
                                try {
                                    bis = new ByteArrayInputStream(blob);
                                    ObjectInputStream in = new ObjectInputStream(bis);
                                    Object o = in.readObject();
                                    map = (HashMap<Long, ArrayList<String>>) o;
                                } finally {
                                    try {
                                        bis.close();
                                    } catch (IOException ex) {
                                        // ignore close exception
                                    }
                                }
                            } else {
                                throw new Exception("Unknown TEST_MODE");
                            }


                            writePolicy.generationPolicy = GenerationPolicy.EXPECT_GEN_EQUAL;
                            writePolicy.generation = recordToUpdate.generation;
                        }

                        if (!map.containsKey(transactionTimestamp)) {
                            map.put(transactionTimestamp, new ArrayList<>(Collections.singletonList(transactionId)));
                        } else {
                            map.get(transactionTimestamp).add(transactionId);
                        }

                        map.keySet().stream().sorted().filter(timestampKey -> timestampKey < transactionTimestampLowWatermark).forEach(map::remove);

                        // Update a key-value in
                        Bin bin_to_update;
                        if (TEST_MODE == TestMode.Json) {
                            String json = mapper.writeValueAsString(map);
                            bin_to_update = new Bin(mapBinName, json);
                        } else if (TEST_MODE == TestMode.Blob) {
                            ByteArrayOutputStream bos = null;
                            try {
                                bos = new ByteArrayOutputStream();
                                ObjectOutputStream out = new ObjectOutputStream(bos);
                                out.writeObject(map);
                                out.flush();
                                byte[] blob = bos.toByteArray();
                                bin_to_update = new Bin(mapBinName, blob);
                            } finally {
                                try {
                                    bos.close();
                                } catch (IOException ex) {
                                    // ignore close exception
                                }
                            }
                        } else {
                            throw new Exception("Unknown TEST_MODE");
                        }

                        // Repeat operation on hot key
                        retry = false;
                        try {
                            client.put(writePolicy, key, bin_to_update);
                        } catch (AerospikeException ae) {
                            //Thread.sleep(random.nextInt(10)); // Back off at a random time, so that other threads can finish updating the same record

                            // Failed? try again
                            if (ae.getResultCode() == ResultCode.GENERATION_ERROR || ae.getResultCode() == ResultCode.KEY_BUSY) {
                                retry = true;
                            } else {
                                throw new Exception(String.format(
                                        "Unexpected set return code: namespace=%s set=%s key=%s bin=%s code=%s",
                                        key.namespace, key.setName, key.userKey, bin_to_update.name, ae.getResultCode()));
                            }
                        }
                    }
                }
                while (retry);

                if (RECORD_CHECK_ENABLED) {
                    Record updatedRecord = client.get(new Policy(), key, mapBinName);
                    Map<Long, List<String>> slidingWindowMap;

                    if (TEST_MODE == TestMode.BinMap) {
                        // Check that we have our specific transaction in the list at the specific timestamp (key)
                        // RATIONALE: other threads may have added their own transaction id at the same time
                        Map<Long, List<String>> map = (Map<Long, List<String>>) updatedRecord.getMap(mapBinName);
                        slidingWindowMap = map;

                    } else if (TEST_MODE == TestMode.Json) {
                        String json = updatedRecord.getString(mapBinName);
                        TypeReference<Map<Long, List<String>>> typeRef = new TypeReference<Map<Long, List<String>>>() {
                        };
                        slidingWindowMap = mapper.readValue(json, typeRef);

                    } else if (TEST_MODE == TestMode.Blob) {
                        byte[] blob = (byte[]) updatedRecord.getValue(mapBinName);
                        ByteArrayInputStream bis = null;
                        try {
                            bis = new ByteArrayInputStream(blob);
                            ObjectInputStream in = new ObjectInputStream(bis);
                            Object o = in.readObject();
                            slidingWindowMap = (Map<Long, List<String>>) o;

                        } finally {
                            try {
                                bis.close();
                            } catch (IOException ex) {
                                // ignore close exception
                            }
                        }
                    } else {
                        throw new Exception("Unknown TEST_MODE");
                    }

                    // - Check: Does out transaction exist?
                    if (!slidingWindowMap.containsKey(transactionTimestamp)) {
                        throw new Exception("\nMissing transaction timestamp: " + transactionTimestamp);
                    } else if (!slidingWindowMap.get(transactionTimestamp).contains(transactionId)) {
                        throw new Exception("\nUnexpected transaction id at timestamp: expected " + transactionId + " actual " + slidingWindowMap.get(transactionTimestamp));
                    }

                    // - Check: Just a finger in the air, there shouldn't be too many items (ie. shouldn't grow) in the sliding window
                    if (slidingWindowMap.values().size() > 50) {
                        throw new Exception("\nUnexpected number of items in window, are old things being removed?");
                    }

                    // - Check: Make sure the sliding window is working - we don't breach the low water mark
                    long firstTransactionTimestamp = slidingWindowMap.keySet().iterator().next();
                    if (firstTransactionTimestamp < transactionTimestampLowWatermark) {
                        throw new Exception("\nUnexpected old items in sliding window detected");
                    }

                    // Show use being busy
                    if (n % 20 == 0) System.out.println(".");
                    System.out.print(".");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        client.close();

        return true;
    }
}
