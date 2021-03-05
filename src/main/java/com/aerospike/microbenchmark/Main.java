package com.aerospike.microbenchmark;

import com.aerospike.client.*;
import com.aerospike.client.Record;
import com.aerospike.client.cdt.*;
import com.aerospike.client.policy.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    private static boolean RECORD_CHECK_ENABLED = false;
    private static int NUMBER_OF_THREADS = 32;
    private static int NUMBER_OF_OPERATIONS_PER_THREAD = 1000;

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

        // Connect to the cluster.
        AerospikeClient client = new AerospikeClient(clientPolicy, new Host("127.0.0.1", 3000));

        try {

            MapPolicy mapPolicy = new MapPolicy(MapOrder.KEY_ORDERED, MapWriteFlags.CREATE_ONLY | MapWriteFlags.NO_FAIL);
            ListPolicy listPolicy = new ListPolicy(ListOrder.UNORDERED, ListWriteFlags.ADD_UNIQUE | ListWriteFlags.NO_FAIL);

            Key key = new Key("test", "demo", "mapkey1");
            client.delete(null, key);
            String mapBinName = "mapbin1";

            int n = NUMBER_OF_OPERATIONS_PER_THREAD;
            while (n-- > 0) {
                long transactionTimestamp = Instant.now().toEpochMilli();
                String transactionId = UUID.randomUUID().toString();

                // Keep only certain timeframe worth of values
                long transactionTimestampLowWatermark = transactionTimestamp - 30;

                boolean retry = false;

                do {

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
                }
                while (retry);

                if (RECORD_CHECK_ENABLED) {
                    // Check that we have our specific transaction in the list at the specific timestamp (key)
                    // RATIONALE: other threads may have added their own transaction id at the same time
                    Record updatedRecord = client.get(new Policy(), key, mapBinName);
                    Map<Long, List<String>> slidingWindowMap = (Map<Long, List<String>>) updatedRecord.getMap(mapBinName);

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
