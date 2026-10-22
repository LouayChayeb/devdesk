package io.github.louch.devdesk.core.stress;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Sends a {@link LoadPlan}'s traffic and reports each second as it ends. */
public interface LoadGenerator {

    /** Runs until the plan's time is up or {@code cancelled} says so. Blocks the calling thread. */
    void run(LoadPlan plan, Consumer<LoadSecond> each, BooleanSupplier cancelled);
}
