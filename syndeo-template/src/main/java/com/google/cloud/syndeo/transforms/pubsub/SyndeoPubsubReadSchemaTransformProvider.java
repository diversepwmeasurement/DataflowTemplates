/*
 * Copyright (C) 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.syndeo.transforms.pubsub;

import com.google.auto.service.AutoService;
import com.google.auto.value.AutoValue;
import com.google.cloud.syndeo.transforms.TypedSchemaTransformProvider;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.schemas.AutoValueSchema;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.annotations.DefaultSchema;
import org.apache.beam.sdk.schemas.transforms.SchemaTransform;
import org.apache.beam.sdk.schemas.transforms.SchemaTransformProvider;
import org.apache.beam.sdk.schemas.utils.AvroUtils;
import org.apache.beam.sdk.schemas.utils.JsonUtils;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionRowTuple;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Strings;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.UnknownKeyFor;

@AutoService(SchemaTransformProvider.class)
public class SyndeoPubsubReadSchemaTransformProvider
    extends TypedSchemaTransformProvider<
        SyndeoPubsubReadSchemaTransformProvider.SyndeoPubsubReadSchemaTransformConfiguration> {

  @Override
  public Class<SyndeoPubsubReadSchemaTransformConfiguration> configurationClass() {
    return SyndeoPubsubReadSchemaTransformConfiguration.class;
  }

  @Override
  public SchemaTransform from(SyndeoPubsubReadSchemaTransformConfiguration configuration) {
    if (configuration.getSubscription() == null && configuration.getTopic() == null) {
      throw new IllegalArgumentException(
          "To read from Pubsub, a subscription name or a topic name must be provided");
    }

    if (configuration.getSubscription() != null && configuration.getTopic() != null) {
      throw new IllegalArgumentException(
          "To read from Pubsub, a subscription name or a topic name must be provided. Not both.");
    }

    if ((Strings.isNullOrEmpty(configuration.getSchema())
            && !Strings.isNullOrEmpty(configuration.getDataFormat()))
        || (!Strings.isNullOrEmpty(configuration.getSchema())
            && Strings.isNullOrEmpty(configuration.getDataFormat()))) {
      throw new IllegalArgumentException(
          "A schema was provided without a data format (or viceversa). Please provide "
              + "both of these parameters to read from Pubsub, or if you would like to use the Pubsub schema service,"
              + " please leave both of these blank.");
    }

    Schema beamSchema;
    SerializableFunction<byte[], Row> valueMapper;
    if (configuration.getSchema() == null && configuration.getDataFormat() == null) {
      try {
        KV<Schema, SerializableFunction<byte[], Row>> schemaFunctionPair =
            SyndeoPubsubUtils.getTopicInfo(
                configuration.getTopic(), configuration.getSubscription());
        beamSchema = schemaFunctionPair.getKey();
        valueMapper = schemaFunctionPair.getValue();
      } catch (IOException e) {
        throw new RuntimeException(
            String.format(
                "Unable to retrieve schema information for topic %s or subscription %s",
                configuration.getTopic(), configuration.getSubscription()),
            e);
      }
    } else {
      if (!Set.of("JSON", "AVRO").contains(configuration.getDataFormat())) {
        throw new IllegalArgumentException(
            String.format(
                "Only JSON and AVRO formats are supported. Received format: %s",
                configuration.getDataFormat()));
      }
      beamSchema =
          Objects.equals(configuration.getDataFormat(), "JSON")
              ? JsonUtils.beamSchemaFromJsonSchema(configuration.getSchema())
              : AvroUtils.toBeamSchema(
                  new org.apache.avro.Schema.Parser().parse(configuration.getSchema()));
      valueMapper =
          Objects.equals(configuration.getDataFormat(), "JSON")
              ? JsonUtils.getJsonBytesToRowFunction(beamSchema)
              : AvroUtils.getAvroBytesToRowFunction(beamSchema);
    }

    return new PubsubReadSchemaTransform(
        configuration.getTopic(), configuration.getSubscription(), beamSchema, valueMapper);
  }

  private static class PubsubReadSchemaTransform implements SchemaTransform, Serializable {
    final Schema beamSchema;
    final SerializableFunction<byte[], Row> valueMapper;
    final @Nullable String topic;
    final @Nullable String subscription;

    PubsubReadSchemaTransform(
        String topic,
        String subscription,
        Schema beamSchema,
        SerializableFunction<byte[], Row> valueMapper) {
      this.topic = topic;
      this.subscription = subscription;
      this.beamSchema = beamSchema;
      this.valueMapper = valueMapper;
    }

    @Override
    public PTransform<PCollectionRowTuple, PCollectionRowTuple> buildTransform() {
      return new PTransform<PCollectionRowTuple, PCollectionRowTuple>() {
        @Override
        public PCollectionRowTuple expand(PCollectionRowTuple input) {
          PubsubIO.Read<PubsubMessage> pubsubRead = PubsubIO.readMessages();
          if (!Strings.isNullOrEmpty(topic)) {
            pubsubRead = pubsubRead.fromTopic(topic);
          } else {
            pubsubRead = pubsubRead.fromSubscription(subscription);
          }
          return PCollectionRowTuple.of(
              "output",
              input
                  .getPipeline()
                  .apply(pubsubRead)
                  .apply(
                      MapElements.into(TypeDescriptors.rows())
                          .via(message -> valueMapper.apply(message.getPayload())))
                  .setRowSchema(beamSchema));
        }
      };
    }
  }

  @Override
  public @UnknownKeyFor @NonNull @Initialized String identifier() {
    return "syndeo:schematransform:com.google.cloud:pubsub_read:v1";
  }

  @Override
  public @UnknownKeyFor @NonNull @Initialized List<@UnknownKeyFor @NonNull @Initialized String>
      inputCollectionNames() {
    return Collections.emptyList();
  }

  @Override
  public @UnknownKeyFor @NonNull @Initialized List<@UnknownKeyFor @NonNull @Initialized String>
      outputCollectionNames() {
    return Collections.singletonList("output");
  }

  @DefaultSchema(AutoValueSchema.class)
  @AutoValue
  public abstract static class SyndeoPubsubReadSchemaTransformConfiguration {
    public abstract @Nullable String getTopic();

    public abstract @Nullable String getSubscription();

    public abstract String getDataFormat(); // AVRO, JSON

    public abstract String getSchema();

    public static Builder builder() {
      return new AutoValue_SyndeoPubsubReadSchemaTransformProvider_SyndeoPubsubReadSchemaTransformConfiguration
          .Builder();
    }

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder setTopic(String topic);

      public abstract Builder setSubscription(String subscription);

      public abstract Builder setDataFormat(String dataFormat);

      public abstract Builder setSchema(String schema);

      public abstract SyndeoPubsubReadSchemaTransformConfiguration build();
    }
  }
}