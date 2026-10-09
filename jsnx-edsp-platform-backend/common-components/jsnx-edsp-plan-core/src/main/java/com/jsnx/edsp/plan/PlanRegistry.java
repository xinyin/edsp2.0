package com.jsnx.edsp.plan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Per-process plan cache. A publish replaces one interface atomically. */
public final class PlanRegistry {
    private final Map<String, AtomicReference<PlanSnapshot>> plans = new ConcurrentHashMap<>();

    public void activate(PlanSnapshot plan) {
        plans.computeIfAbsent(plan.interfaceCode(), ignored -> new AtomicReference<>()).set(plan);
    }

    public PlanSnapshot get(String interfaceCode) {
        AtomicReference<PlanSnapshot> ref = plans.get(interfaceCode);
        return ref == null ? null : ref.get();
    }

    public int size() { return plans.size(); }
}
