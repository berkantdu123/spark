/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.monitor.tick;

import com.sun.management.GarbageCollectionNotificationInfo;
import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.monitor.memory.GarbageCollectionMonitor;
import me.lucko.spark.common.sampler.tick.TickHook;
import net.kyori.text.Component;
import net.kyori.text.TextComponent;
import net.kyori.text.format.TextColor;

import java.text.DecimalFormat;
import java.util.DoubleSummaryStatistics;

public abstract class TickMonitor implements TickHook.Callback, GarbageCollectionMonitor.Listener, AutoCloseable {
    private static final DecimalFormat df = new DecimalFormat("#.##");

    private final SparkPlatform platform;
    private final TickHook tickHook;
    private final int zeroTick;
    private final GarbageCollectionMonitor garbageCollectionMonitor;
    private final ReportPredicate reportPredicate;

    // data
    private volatile double lastTickTime = 0;
    private State state = null;
    private final DoubleSummaryStatistics averageTickTime = new DoubleSummaryStatistics();
    private double avg;

    public TickMonitor(SparkPlatform platform, TickHook tickHook, ReportPredicate reportPredicate, boolean monitorGc) {
        this.platform = platform;
        this.tickHook = tickHook;
        this.zeroTick = tickHook.getCurrentTick();
        this.reportPredicate = reportPredicate;

        if (monitorGc) {
            this.garbageCollectionMonitor =  new GarbageCollectionMonitor();
            this.garbageCollectionMonitor.addListener(this);
        } else {
            this.garbageCollectionMonitor = null;
        }
    }

    public int getCurrentTick() {
        return this.tickHook.getCurrentTick() - this.zeroTick;
    }

    protected abstract void sendMessage(Component message);

    @Override
    public void close() {
        if (this.garbageCollectionMonitor != null) {
            this.garbageCollectionMonitor.close();
        }
    }

    @Override
    public void onTick(int currentTick) {
        double now = ((double) System.nanoTime()) / 1000000d;

        // init
        if (this.state == null) {
            this.state = State.SETUP;
            this.lastTickTime = now;
            sendMessage(TextComponent.of("Tick monitor started. Before the monitor becomes fully active, the server's " +
                    "average tick rate will be calculated over a period of 120 ticks (approx 6 seconds)."));
            return;
        }

        // find the diff
        double last = this.lastTickTime;
        double tickDuration = now - last;
        this.lastTickTime = now;

        if (last == 0) {
            return;
        }

        // form averages
        if (this.state == State.SETUP) {
            this.averageTickTime.accept(tickDuration);

            // move onto the next state
            if (this.averageTickTime.getCount() >= 120) {
                this.platform.getPlugin().executeAsync(() -> {
                    sendMessage(TextComponent.of("Analysis is now complete.", TextColor.GOLD));
                    sendMessage(TextComponent.builder("").color(TextColor.GRAY)
                            .append(TextComponent.of(">", TextColor.WHITE))
                            .append(TextComponent.space())
                            .append(TextComponent.of("Max: "))
                            .append(TextComponent.of(df.format(this.averageTickTime.getMax())))
                            .append(TextComponent.of("ms"))
                            .build()
                    );
                    sendMessage(TextComponent.builder("").color(TextColor.GRAY)
                            .append(TextComponent.of(">", TextColor.WHITE))
                            .append(TextComponent.space())
                            .append(TextComponent.of("Min: "))
                            .append(TextComponent.of(df.format(this.averageTickTime.getMin())))
                            .append(TextComponent.of("ms"))
                            .build()
                    );
                    sendMessage(TextComponent.builder("").color(TextColor.GRAY)
                            .append(TextComponent.of(">", TextColor.WHITE))
                            .append(TextComponent.space())
                            .append(TextComponent.of("Average: "))
                            .append(TextComponent.of(df.format(this.averageTickTime.getAverage())))
                            .append(TextComponent.of("ms"))
                            .build()
                    );
                    sendMessage(this.reportPredicate.monitoringStartMessage());
                });

                this.avg = this.averageTickTime.getAverage();
                this.state = State.MONITORING;
            }
        }

        if (this.state == State.MONITORING) {
            double increase = tickDuration - this.avg;
            double percentageChange = (increase * 100d) / this.avg;
            if (this.reportPredicate.shouldReport(tickDuration, increase, percentageChange)) {
                this.platform.getPlugin().executeAsync(() -> {
                    sendMessage(TextComponent.builder("").color(TextColor.GRAY)
                            .append(TextComponent.of("Tick "))
                            .append(TextComponent.of("#" + getCurrentTick(), TextColor.DARK_GRAY))
                            .append(TextComponent.of(" lasted "))
                            .append(TextComponent.of(df.format(tickDuration), TextColor.GOLD))
                            .append(TextComponent.of(" ms. "))
                            .append(TextComponent.of("("))
                            .append(TextComponent.of(df.format(percentageChange) + "%", TextColor.GOLD))
                            .append(TextComponent.of(" increase from avg)"))
                            .build()
                    );
                });
            }
        }
    }

    @Override
    public void onGc(GarbageCollectionNotificationInfo data) {
        if (this.state == State.SETUP) {
            // set lastTickTime to zero so this tick won't be counted in the average
            this.lastTickTime = 0;
            return;
        }

        String gcType;
        if (data.getGcAction().equals("end of minor GC")) {
            gcType = "Young Gen";
        } else if (data.getGcAction().equals("end of major GC")) {
            gcType = "Old Gen";
        } else {
            gcType = data.getGcAction();
        }

        this.platform.getPlugin().executeAsync(() -> {
            sendMessage(TextComponent.builder("").color(TextColor.GRAY)
                    .append(TextComponent.of("Tick "))
                    .append(TextComponent.of("#" + getCurrentTick(), TextColor.DARK_GRAY))
                    .append(TextComponent.of(" included "))
                    .append(TextComponent.of("GC", TextColor.RED))
                    .append(TextComponent.of(" lasting "))
                    .append(TextComponent.of(df.format(data.getGcInfo().getDuration()), TextColor.GOLD))
                    .append(TextComponent.of(" ms. (type = " + gcType + ")"))
                    .build()
            );
        });
    }

    /**
     * A predicate to test whether a tick should be reported.
     */
    public interface ReportPredicate {

        /**
         * Tests whether a tick should be reported.
         *
         * @param duration the tick duration
         * @param increaseFromAvg the difference between the ticks duration and the average
         * @param percentageChange the percentage change between the ticks duration and the average
         * @return true if the tick should be reported, false otherwise
         */
        boolean shouldReport(double duration, double increaseFromAvg, double percentageChange);

        /**
         * Gets a component to describe how the predicate will select ticks to report.
         *
         * @return the component
         */
        Component monitoringStartMessage();

        final class PercentageChangeGt implements ReportPredicate {
            private final double threshold;

            public PercentageChangeGt(double threshold) {
                this.threshold = threshold;
            }

            @Override
            public boolean shouldReport(double duration, double increaseFromAvg, double percentageChange) {
                if (increaseFromAvg <= 0) {
                    return false;
                }
                return percentageChange > this.threshold;
            }

            @Override
            public Component monitoringStartMessage() {
                return TextComponent.of("Starting now, any ticks with >" + this.threshold + "% increase in " +
                        "duration compared to the average will be reported.");
            }
        }

        final class DurationGt implements ReportPredicate {
            private final double threshold;

            public DurationGt(double threshold) {
                this.threshold = threshold;
            }

            @Override
            public boolean shouldReport(double duration, double increaseFromAvg, double percentageChange) {
                if (increaseFromAvg <= 0) {
                    return false;
                }
                return duration > this.threshold;
            }

            @Override
            public Component monitoringStartMessage() {
                return TextComponent.of("Starting now, any ticks with duration >" + this.threshold + " will be reported.");
            }
        }

    }

    private enum State {
        SETUP,
        MONITORING
    }
}
