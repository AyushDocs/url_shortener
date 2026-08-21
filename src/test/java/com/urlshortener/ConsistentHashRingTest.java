package com.urlshortener;

import com.urlshortener.distributed.hashring.ConsistentHashRing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashRingTest {

    @Nested
    class Determinism {
        @Test
        void sameKeyAlwaysRoutesSame() {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                    List.of("A", "B", "C"), 150);

            String key = "aZ91xK";
            String expected = ring.getNode(key);
            for (int i = 0; i < 10_000; i++) {
                assertEquals(expected, ring.getNode(key));
            }
        }

        @Test
        void differentKeysCanRouteDifferent() {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                    List.of("A", "B", "C"), 150);

            Set<String> targets = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                targets.add(ring.getNode("key-" + i));
            }
            assertTrue(targets.size() > 1, "Keys should distribute across multiple nodes");
        }
    }

    @Nested
    class Distribution {
        @Test
        void distributesAcrossAllNodes() {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                    List.of("A", "B", "C"), 150);

            Map<String, AtomicInteger> counts = new HashMap<>();
            for (String node : List.of("A", "B", "C")) {
                counts.put(node, new AtomicInteger());
            }

            for (int i = 0; i < 30_000; i++) {
                String target = ring.getNode("code-" + i);
                counts.get(target).incrementAndGet();
            }

            for (Map.Entry<String, AtomicInteger> entry : counts.entrySet()) {
                int count = entry.getValue().get();
                double percentage = (count / 30_000.0) * 100;
                assertTrue(count > 5000,
                        "Node " + entry.getKey() + " got " + count +
                                " (" + String.format("%.1f", percentage) + "%) — too unbalanced");
            }
        }

        @Test
        void moreVirtualNodesMeansBetterBalance() {
            ConsistentHashRing<String> ringLow = new ConsistentHashRing<>(
                    List.of("A", "B", "C"), 10);
            ConsistentHashRing<String> ringHigh = new ConsistentHashRing<>(
                    List.of("A", "B", "C"), 500);

            double deviationLow = measureDeviation(ringLow, 10_000);
            double deviationHigh = measureDeviation(ringHigh, 10_000);

            assertTrue(deviationHigh < deviationLow,
                    "More virtual nodes should give better balance: low=" +
                            String.format("%.2f", deviationLow) + " high=" +
                            String.format("%.2f", deviationHigh));
        }

        private double measureDeviation(ConsistentHashRing<String> ring, int keyCount) {
            Map<String, Integer> counts = new HashMap<>();
            for (int i = 0; i < keyCount; i++) {
                counts.merge(ring.getNode("k-" + i), 1, Integer::sum);
            }
            double expected = (double) keyCount / 3;
            double variance = 0;
            for (int c : counts.values()) {
                variance += Math.pow(c - expected, 2);
            }
            return Math.sqrt(variance / 3);
        }
    }

    @Nested
    class Rebalancing {
        @Test
        void addingNodeRedistributesSomeKeys() {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                    List.of("A", "B"), 150);

            Map<String, String> before = new HashMap<>();
            for (int i = 0; i < 10_000; i++) {
                before.put("k-" + i, ring.getNode("k-" + i));
            }

            ring.addNode("C");

            int changed = 0;
            for (int i = 0; i < 10_000; i++) {
                if (!before.get("k-" + i).equals(ring.getNode("k-" + i))) {
                    changed++;
                }
            }

            assertTrue(changed > 0, "Some keys should move to the new node");
            assertTrue(changed < 10_000, "Most keys should stay put: " + changed + " moved");
            double movedPct = (changed / 10_000.0) * 100;
            assertTrue(movedPct < 60,
                    "Only ~1/3 should move, got " + String.format("%.1f", movedPct) + "%");
        }

        @Test
        void removingNodeRedistributesAffectedKeys() {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                    List.of("A", "B", "C"), 150);

            Map<String, String> before = new HashMap<>();
            for (int i = 0; i < 10_000; i++) {
                before.put("k-" + i, ring.getNode("k-" + i));
            }

            ring.removeNode("B");

            int moved = 0;
            int stayedOnAOrC = 0;
            for (int i = 0; i < 10_000; i++) {
                String after = ring.getNode("k-" + i);
                if (!before.get("k-" + i).equals(after)) {
                    moved++;
                    assertTrue(after.equals("A") || after.equals("C"),
                            "Moved key should go to A or C, got " + after);
                } else {
                    stayedOnAOrC++;
                }
            }

            assertTrue(moved > 0, "Keys on B should move");
            assertTrue(stayedOnAOrC > 5000, "Most keys on A and C should stay");
        }

        @Test
        void ringSizeReflectsNodeCount() {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                    List.of("A", "B"), 150);
            int size2 = ring.getRingSize();

            ring.addNode("C");
            int size3 = ring.getRingSize();

            ring.removeNode("C");
            int size2Again = ring.getRingSize();

            assertEquals(size2, size2Again);
            assertTrue(size3 > size2);
        }
    }

    @Nested
    class EdgeCases {
        @Test
        void singleNodeRoutesAllToIt() {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                    List.of("only"), 150);

            for (int i = 0; i < 1000; i++) {
                assertEquals("only", ring.getNode("key-" + i));
            }
        }

        @Test
        void emptyRingThrows() {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                    List.of("A"), 150);
            ring.removeNode("A");

            assertThrows(IllegalStateException.class, () -> ring.getNode("any"));
        }

        @Test
        void wrappingAroundTheRing() {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                    List.of("A", "B"), 150);

            assertDoesNotThrow(() -> {
                for (int i = 0; i < 10_000; i++) {
                    ring.getNode(UUID.randomUUID().toString());
                }
            });
        }

        @Test
        void threadSafety() throws Exception {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                    List.of("A", "B", "C"), 150);

            ExecutorService executor = Executors.newFixedThreadPool(8);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < 1000; i++) {
                final String key = "key-" + i;
                futures.add(executor.submit(() -> ring.getNode(key)));
            }

            for (int i = 0; i < futures.size(); i++) {
                String result = futures.get(i).get();
                assertEquals(ring.getNode("key-" + i), result,
                        "Concurrent reads should be consistent");
            }

            executor.shutdown();
        }
    }
}
