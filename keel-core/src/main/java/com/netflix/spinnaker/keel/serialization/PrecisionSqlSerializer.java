package com.netflix.spinnaker.keel.serialization;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializerBase;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

public class PrecisionSqlSerializer extends InstantSerializerBase<Instant> {
  public PrecisionSqlSerializer() {
    super(
        Instant.class,
        Instant::toEpochMilli,
        Instant::getEpochSecond,
        Instant::getNano,
        new DateTimeFormatterBuilder().appendInstant(3).toFormatter());
  }

  @Override
  protected InstantSerializerBase<?> withFormat(
      Boolean aBoolean, DateTimeFormatter dateTimeFormatter, JsonFormat.Shape shape) {
    return this;
  }
}
