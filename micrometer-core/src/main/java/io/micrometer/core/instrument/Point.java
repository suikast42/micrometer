package io.micrometer.core.instrument;

import io.micrometer.core.instrument.config.NamingConvention;

import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author: vuru
 * Date: 26.05.2018
 * Time: 20:12
 */
public class Point  {
    private final String measurement;
    private final Map<String, String> tags;
    private Long time;
    private final Map<String, Object> fields;

    public Point(final String measurement) {
        this.measurement = measurement;
        tags = new HashMap<>();
        fields = new HashMap<>();
        time(System.currentTimeMillis(),TimeUnit.MILLISECONDS) ;
    }

    public Point time(final long timeToSet,TimeUnit pressission) {
        this.time = getPrecision().convert(timeToSet,pressission);
        return this;
    }

    public Point cloneMeasurement() {
        return new Point(this.measurement);
    }

    public Point tag(final String tagName, final String value) {
        Objects.requireNonNull(tagName, "tagName");
        Objects.requireNonNull(value, "value");
        if (!tagName.isEmpty() && !value.isEmpty()) {
            tags.put(tagName, value);
        }
        return this;
    }
    public Point field(final String field, Object value) {
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

    public void setTime(Long time) {
        this.time = time;
    }

    public String getMeasurement(MeterRegistry registry) {
        final NamingConvention namingConvention = registry.config().namingConvention();
        return namingConvention.name(measurement, Meter.Type.OTHER);
    }

    public Map<String, String> getTags(MeterRegistry registry) {
        final NamingConvention namingConvention = registry.config().namingConvention();
        Map<String,String> result = new HashMap<>() ;
        final Set<Tag> commonTags = registry.config().getCommonTags();
       commonTags.forEach( tag ->{
           result.put(namingConvention.tagKey(tag.getKey()),namingConvention.tagValue(tag.getValue()));
       });
        tags.forEach((k,v)->{
            result.put(namingConvention.tagKey(k),namingConvention.tagValue(v));
        });
        return result;
    }
    public Map<String, Object> getFields(MeterRegistry registry) {
        final NamingConvention namingConvention = registry.config().namingConvention();
        Map<String,Object> result = new HashMap<>() ;
        fields.forEach((k,v)->{
            result.put(namingConvention.tagKey(k),v);
        });
        return result;
    }

    public TimeUnit getPrecision() {
        return TimeUnit.NANOSECONDS;
    }
}
