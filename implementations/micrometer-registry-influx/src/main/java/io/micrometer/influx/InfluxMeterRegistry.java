/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.influx;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.util.DoubleFormat;
import io.micrometer.core.instrument.util.IOUtils;
import io.micrometer.core.instrument.util.MeterPartition;
import io.micrometer.core.instrument.util.StringUtils;
import io.micrometer.core.lang.Nullable;
import org.influxdb.BatchOptions;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPOutputStream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

/**
 * @author Jon Schneider
 */
public class InfluxMeterRegistry extends StepMeterRegistry {
    private final InfluxConfig config;
    private final Logger logger = LoggerFactory.getLogger(InfluxMeterRegistry.class);
    private boolean databaseExists = false;
    private InfluxDB influxDB ;


    public InfluxMeterRegistry(InfluxConfig config, Clock clock, ThreadFactory threadFactory) {
        super(config, clock);
        this.config().namingConvention(new InfluxNamingConvention());
        this.config = config;
        start(threadFactory);
        // TODO: 27.05.2018 username and password 
        influxDB=  InfluxDBFactory.connect(config.uri());
        influxDB.setDatabase(config.db());
        // TODO: 27.05.2018 Rentition policy 
//        influxDB.setRetentionPolicy(config.retentionPolicy()) ;
        // TODO: 27.05.2018 Move this to config
        // Flush every 2000 Points, at least every 1000 ms
        influxDB.enableBatch(BatchOptions.DEFAULTS.flushDuration(1_000).actions(2000).exceptionHandler(
            (failedPoints, throwable) -> {
                final List<String> failed = StreamSupport.stream(failedPoints.spliterator(), false)
                    .map(point -> point.lineProtocol()).collect(Collectors.toList());
                logger.error("Can't write points "+ String.join("\n", failed), throwable);})
        );
    }

    public InfluxMeterRegistry(InfluxConfig config, Clock clock) {
        this(config, clock, Executors.defaultThreadFactory());
    }

    private void createDatabaseIfNecessary() {
        if (!config.autoCreateDb() || databaseExists)
            return;

        HttpURLConnection con = null;
        try {
            URL queryEndpoint = URI.create(config.uri() + "/query?q=" + URLEncoder.encode("CREATE DATABASE \"" + config.db() + "\"", "UTF-8")).toURL();

            con = (HttpURLConnection) queryEndpoint.openConnection();
            con.setConnectTimeout((int) config.connectTimeout().toMillis());
            con.setReadTimeout((int) config.readTimeout().toMillis());
            con.setRequestMethod("POST");
            authenticateRequest(con);

            int status = con.getResponseCode();

            if (status >= 200 && status < 300) {
                logger.debug("influx database {} is ready to receive metrics", config.db());
                databaseExists = true;
            } else if (status >= 400) {
                if (logger.isErrorEnabled()) {
                    logger.error("unable to create database '{}': {}", config.db(), IOUtils.toString(con.getErrorStream()));
                }
            }
        } catch (Throwable e) {
            logger.error("unable to create database '{}'", config.db(), e);
        } finally {
            quietlyCloseUrlConnection(con);
        }
    }
    public void measure(Point point) {
        if(!config.enabled()){
            return;
        }
        createDatabaseIfNecessary();
        // TODO: 27.05.2018 Make async ???
        final org.influxdb.dto.Point influxpoint = org.influxdb.dto.Point.measurement(point.getMeasurement(this))
            .fields(point.getFields(this))
            .tag(point.getTags(this))
            .time(point.getTime(), point.getPrecision())
            .build();
        influxDB.write(influxpoint);
    }
    
    @Override
    protected void publish() {
        createDatabaseIfNecessary();

        try {
            String write = "/write?consistency=" + config.consistency().toString().toLowerCase() + "&precision=ms&db=" + config.db();
            if (StringUtils.isNotBlank(config.retentionPolicy())) {
                write += "&rp=" + config.retentionPolicy();
            }
            URL influxEndpoint = URI.create(config.uri() + write).toURL();
            HttpURLConnection con = null;

            for (List<Meter> batch : MeterPartition.partition(this, config.batchSize())) {
                try {
                    con = (HttpURLConnection) influxEndpoint.openConnection();
                    con.setConnectTimeout((int) config.connectTimeout().toMillis());
                    con.setReadTimeout((int) config.readTimeout().toMillis());
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "plain/text");
                    con.setDoOutput(true);

                    authenticateRequest(con);

                    List<String> bodyLines = batch.stream()
                            .flatMap(m -> {
                                if (m instanceof Timer) {
                                    return writeTimer((Timer) m);
                                }
                                if (m instanceof DistributionSummary) {
                                    return writeSummary((DistributionSummary) m);
                                }
                                if (m instanceof FunctionTimer) {
                                    return writeTimer((FunctionTimer) m);
                                }
                                if (m instanceof TimeGauge) {
                                    return writeGauge(m.getId(), ((TimeGauge) m).value(getBaseTimeUnit()));
                                }
                                if (m instanceof Gauge) {
                                    return writeGauge(m.getId(), ((Gauge) m).value());
                                }
                                if (m instanceof FunctionCounter) {
                                    return writeCounter(m.getId(), ((FunctionCounter) m).count());
                                }
                                if (m instanceof Counter) {
                                    return writeCounter(m.getId(), ((Counter) m).count());
                                }
                                if (m instanceof LongTaskTimer) {
                                    return writeLongTaskTimer((LongTaskTimer) m);
                                }
                                return writeMeter(m);
                            })
                            .collect(toList());

                    String body = String.join("\n", bodyLines);

                    if (config.compressed())
                        con.setRequestProperty("Content-Encoding", "gzip");

                    try (OutputStream os = con.getOutputStream()) {
                        if (config.compressed()) {
                            try (GZIPOutputStream gz = new GZIPOutputStream(os)) {
                                gz.write(body.getBytes());
                                gz.flush();
                            }
                        } else {
                            os.write(body.getBytes());
                        }
                        os.flush();
                    }

                    int status = con.getResponseCode();

                    if (status >= 200 && status < 300) {
                        logger.info("successfully sent {} metrics to influx", batch.size());
                        databaseExists = true;
                    } else if (status >= 400) {
                        if (logger.isErrorEnabled()) {
                            logger.error("failed to send metrics: {}", IOUtils.toString(con.getErrorStream()));
                        }
                    } else {
                        logger.error("failed to send metrics: http {}", status);
                    }

                } finally {
                    quietlyCloseUrlConnection(con);
                }
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Malformed InfluxDB publishing endpoint, see '" + config.prefix() + ".uri'", e);
        } catch (Throwable e) {
            logger.error("failed to send metrics", e);
        }
    }

    private void authenticateRequest(HttpURLConnection con) {
        if (config.userName() != null && config.password() != null) {
            String encoded = Base64.getEncoder().encodeToString((config.userName() + ":" +
                    config.password()).getBytes(StandardCharsets.UTF_8));
            con.setRequestProperty("Authorization", "Basic " + encoded);
        }
    }

    private void quietlyCloseUrlConnection(@Nullable HttpURLConnection con) {
        try {
            if (con != null) {
                con.disconnect();
            }
        } catch (Exception ignore) {
        }
       
    }

    @Override
    public void close() {
        super.close();
        try {
            if (influxDB != null) {
                influxDB.close();
            }
        } catch (Exception ignore) {
        }
    }

    class Field {
        final String key;
        final double value;

        Field(String key, double value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString() {
            return key + "=" + DoubleFormat.decimalOrNan(value);
        }
    }

    private Stream<String> writeMeter(Meter m) {
        Stream.Builder<Field> fields = Stream.builder();

        for (Measurement measurement : m.measure()) {
            String fieldKey = measurement.getStatistic().toString()
                    .replaceAll("(.)(\\p{Upper})", "$1_$2").toLowerCase();
            fields.add(new Field(fieldKey, measurement.getValue()));
        }

        return Stream.of(influxLineProtocol(m.getId(), "unknown", fields.build(), clock.wallTime()));
    }

    private Stream<String> writeLongTaskTimer(LongTaskTimer timer) {
        Stream<Field> fields = Stream.of(
                new Field("active_tasks", timer.activeTasks()),
                new Field("duration", timer.duration(getBaseTimeUnit()))
        );
        return Stream.of(influxLineProtocol(timer.getId(), "long_task_timer", fields, clock.wallTime()));
    }

    private Stream<String> writeCounter(Meter.Id id, double count) {
        return Stream.of(influxLineProtocol(id, "counter", Stream.of(new Field("value", count)), clock.wallTime()));
    }

    private Stream<String> writeGauge(Meter.Id id, Double value) {
        return value.isNaN() ? Stream.empty() :
            Stream.of(influxLineProtocol(id, "gauge", Stream.of(new Field("value", value)), clock.wallTime()));
    }

    private Stream<String> writeTimer(FunctionTimer timer) {
        Stream<Field> fields = Stream.of(
                new Field("sum", timer.totalTime(getBaseTimeUnit())),
                new Field("count", timer.count()),
                new Field("mean", timer.mean(getBaseTimeUnit()))
        );

        return Stream.of(influxLineProtocol(timer.getId(), "histogram", fields, clock.wallTime()));
    }

    private Stream<String> writeTimer(Timer timer) {
        final Stream.Builder<Field> fields = Stream.builder();

        fields.add(new Field("sum", timer.totalTime(getBaseTimeUnit())));
        fields.add(new Field("count", timer.count()));
        fields.add(new Field("mean", timer.mean(getBaseTimeUnit())));
        fields.add(new Field("upper", timer.max(getBaseTimeUnit())));

        return Stream.of(influxLineProtocol(timer.getId(), "histogram", fields.build(), clock.wallTime()));
    }

    private Stream<String> writeSummary(DistributionSummary summary) {
        final Stream.Builder<Field> fields = Stream.builder();

        fields.add(new Field("sum", summary.totalAmount()));
        fields.add(new Field("count", summary.count()));
        fields.add(new Field("mean", summary.mean()));
        fields.add(new Field("upper", summary.max()));

        return Stream.of(influxLineProtocol(summary.getId(), "histogram", fields.build(), clock.wallTime()));
    }

    private String influxLineProtocol(Meter.Id id, String metricType, Stream<Field> fields, long time) {
        String tags = getConventionTags(id).stream()
                .map(t -> "," + t.getKey() + "=" + t.getValue())
                .collect(joining(""));

        return getConventionName(id)
                + tags + ",metric_type=" + metricType + " "
                + fields.map(Field::toString).collect(joining(","))
                + " " + time;
    }

    @Override
    protected final TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }

}
