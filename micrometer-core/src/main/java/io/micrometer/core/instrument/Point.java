package io.micrometer.core.instrument;

import io.micrometer.core.instrument.config.NamingConvention;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author: vuru
 * Date: 26.05.2018
 * Time: 20:12
 */
public class Point {

    private String measurement;
    private Map<String, String> tags;
    private Map<String, Object> fields;
    private Long time;

    Point() {
    }

    public static Builder builder(){
        return builder(null);
    }

    public static Builder builder(String measurement){
        return new Point().new Builder().measurement(measurement);
    }



    private Point time(final long timeToSet, TimeUnit pressission) {
        this.time = getPrecision().convert(timeToSet, pressission);
        return this;
    }


    private Point measurement(String measurement) {
        this.measurement = measurement;
        return this;
    }


    private Point tags(Map<String, String> tags) {
        this.tags = tags;
        return this;
    }

    private Point fields(Map<String, Object> fields) {
        this.fields = fields;
        return this;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Point point = (Point) o;
        return Objects.equals(measurement, point.measurement)
            && Objects.equals(tags, point.tags)
            && Objects.equals(time, point.time)
            && Objects.equals(fields, point.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(measurement, tags, time, fields);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Point [name=");
        builder.append(this.measurement);
        if (this.time != null) {
            builder.append(", time=");
            builder.append(this.time);
        }
        builder.append(", tags=");
        builder.append(this.tags);
        builder.append(", fields=");
        builder.append(this.fields);
        builder.append("]");
        return builder.toString();
    }

    public Long getTime() {
        return time;
    }

    public String getMeasurement(MeterRegistry registry) {
        final NamingConvention namingConvention = registry.config().namingConvention();
        return namingConvention.name(measurement, Meter.Type.OTHER);
    }

    public Map<String, String> getTags(MeterRegistry registry) {
        final NamingConvention namingConvention = registry.config().namingConvention();
        Map<String, String> result = new HashMap<>();
        final Set<Tag> commonTags = registry.config().getCommonTags();
        commonTags.forEach(tag -> {
            result.put(namingConvention.tagKey(tag.getKey()), namingConvention.tagValue(tag.getValue()));
        });
        tags.forEach((k, v) -> {
            result.put(namingConvention.tagKey(k), namingConvention.tagValue(v));
        });
        return result;
    }

    public Map<String, Object> getFields(MeterRegistry registry) {
        final NamingConvention namingConvention = registry.config().namingConvention();
        Map<String, Object> result = new HashMap<>();
        fields.forEach((k, v) -> {
            result.put(namingConvention.tagKey(k), v);
        });
        return result;
    }

    public TimeUnit getPrecision() {
        return TimeUnit.NANOSECONDS;
    }
    private static  ExecutorService executorService = Executors.newCachedThreadPool();

    public class Builder {
        private String measurement;
        private Map<String, String> tags;
        private Map<String, Object> fields;

        private Builder() {
            tags = new HashMap<>() ;
            fields = new HashMap<>();
        }

        public Builder measurement(String measurement) {
            this.measurement = measurement;
            return this;
        }

        public Builder reset() {
            resetFields();
            resetTags();
            return this;
        }

        public Builder resetTags() {
            tags = new HashMap<>() ;
            return this;
        }

        public Builder resetFields() {
            fields = new HashMap<>();
            return this;
        }

        public Builder tag(final String tagName, final String value) {
            Objects.requireNonNull(tagName, "tagName");
            Objects.requireNonNull(value, "value");
            if (!tagName.isEmpty() && !value.isEmpty()) {
                tags.put(tagName, value);
            }
            return this;
        }

        public Builder field(final String field, Object value) {
            if (value instanceof Number) {
                if (value instanceof Byte) {
                    value = ((Byte) value).doubleValue();
                } else if (value instanceof Short) {
                    value = ((Short) value).doubleValue();
                } else if (value instanceof Integer) {
                    value = ((Integer) value).doubleValue();
                } else if (value instanceof Long) {
                    value = ((Long) value).doubleValue();
                } else if (value instanceof BigInteger) {
                    value = ((BigInteger) value).doubleValue();
                }
            }
            fields.put(field, value);
            return this;
        }

        public Point build() {
            return new Point().measurement(measurement).fields(fields).tags(tags).time(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        /**
         * Async push
         * @param registry
         */
        public void push (MeterRegistry registry) {
            executorService.execute(()-> registry.measure(this.build()));
        }
    }
}
