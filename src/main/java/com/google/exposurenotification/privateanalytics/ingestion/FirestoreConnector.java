/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.exposurenotification.privateanalytics.ingestion;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ServerStream;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.v1.FirestoreClient;
import com.google.cloud.firestore.v1.FirestoreClient.PartitionQueryPagedResponse;
import com.google.cloud.firestore.v1.FirestoreSettings;
import com.google.firestore.v1.BatchWriteRequest;
import com.google.firestore.v1.BatchWriteResponse;
import com.google.firestore.v1.Cursor;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.PartitionQueryRequest;
import com.google.firestore.v1.RunQueryRequest;
import com.google.firestore.v1.RunQueryResponse;
import com.google.firestore.v1.StructuredQuery;
import com.google.firestore.v1.StructuredQuery.CollectionSelector;
import com.google.firestore.v1.StructuredQuery.Direction;
import com.google.firestore.v1.StructuredQuery.FieldReference;
import com.google.firestore.v1.StructuredQuery.Order;
import com.google.firestore.v1.Write;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.Distinct;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.bp.LocalDateTime;
import org.threeten.bp.ZoneOffset;
import org.threeten.bp.format.DateTimeFormatter;

/**
 * Primitive beam connector for Firestore native specific to ENPA.
 *
 * <p>For a general purpose connector see https://issues.apache.org/jira/browse/BEAM-8376
 */
public class FirestoreConnector {

  private static final Logger LOG = LoggerFactory.getLogger(FirestoreConnector.class);

  private static final long SECONDS_IN_HOUR = Duration.of(1, ChronoUnit.HOURS).getSeconds();

  // Order must be name ascending. Right now, this is the only ordering that the
  // Firestore SDK supports.
  private static final String NAME_FIELD = "__name__";

  private static final Duration FIRESTORE_WAIT_TIME = Duration.ofSeconds(30);

  private static final Counter queriesGenerated =
      Metrics.counter(FirestoreConnector.class, "queriesGenerated");

  private static final Counter partitionCursors =
      Metrics.counter(FirestoreConnector.class, "partitionCursors");

  private static final Counter grpcException =
      Metrics.counter(FirestoreConnector.class, "grpcException");

  private static final Counter documentsRead =
      Metrics.counter(FirestoreConnector.class, "documentsRead");

  private static final Counter documentsDeleted =
      Metrics.counter(FirestoreConnector.class, "documentsDeleted");

  private static final Counter failedDeletes =
      Metrics.counter(FirestoreConnector.class, "failedDeletes");

  private static final Counter skippedResults =
      Metrics.counter(FirestoreConnector.class, "skippedResults");

  private static final Counter partialProgress =
      Metrics.counter(FirestoreConnector.class, "partialProgress");

  /** Reads documents from Firestore */
  public static final class FirestoreReader extends PTransform<PBegin, PCollection<Document>> {

    @Override
    public PCollection<Document> expand(PBegin input) {
      IngestionPipelineOptions options =
          (IngestionPipelineOptions) input.getPipeline().getOptions();
      LOG.info("IngestionPipelineOptions: " + IngestionPipelineOptions.displayString(options));
      long start =
          IngestionPipelineOptions.calculatePipelineStart(
              options.getStartTime(), options.getDuration(), Clock.systemUTC());
      LOG.info("calculated start time in seconds as: {}", start);
      long backwardHours = options.getGraceHoursBackwards();
      long forwardHours = options.getGraceHoursForwards() + options.getDuration() / SECONDS_IN_HOUR;
      return input
          .apply("Begin", Create.of(generateQueries(start, backwardHours, forwardHours)))
          .apply("PartitionQuery", ParDo.of(new PartitionQueryFn()))
          .apply("Read", ParDo.of(new ReadFn()))
          // In case workers retried on some shards and duplicates got emitted, ensure distinctness
          .apply(
              Distinct.<Document, String>withRepresentativeValueFn(
                  // Not using a lambda here as Beam has trouble inferring a coder
                  new SerializableFunction<Document, String>() {
                    @Override
                    public String apply(Document document) {
                      return document.getName();
                    }
                  }));
    }

    private Iterable<StructuredQuery> generateQueries(
        long startTime, long backwardHours, long forwardHours) {
      List<StructuredQuery> structuredQueries = new ArrayList<>();
      // Each document in Firestore is stored under a Date collection with the format:
      // yyyy-MM-dd-HH.
      // To query all documents uploaded around startTime within the specified window, construct
      // a query for each hour within the window: [startTime - backwardHours, startTime +
      // forwardHours].
      for (long i = (-1 * backwardHours); i <= forwardHours; i++) {
        long timeToQuery = startTime + i * SECONDS_IN_HOUR;
        // Reformat the date to mirror the format of documents in Firestore: yyyy-MM-dd-HH.
        String formattedDateTime = formatDateTime(timeToQuery);
        // Construct and output query.
        StructuredQuery query =
            StructuredQuery.newBuilder()
                .addFrom(
                    CollectionSelector.newBuilder()
                        .setCollectionId(formattedDateTime)
                        .setAllDescendants(true)
                        .build())
                .addOrderBy(
                    Order.newBuilder()
                        .setField(FieldReference.newBuilder().setFieldPath(NAME_FIELD).build())
                        .setDirection(Direction.ASCENDING)
                        .build())
                .build();
        structuredQueries.add(query);
        queriesGenerated.inc();
      }
      return structuredQueries;
    }

    static class PartitionQueryFn
        extends DoFn<StructuredQuery, ImmutableTriple<Cursor, Cursor, StructuredQuery>> {

      private FirestoreClient client;

      @StartBundle
      public void startBundle() throws Exception {
        client = getFirestoreClient();
      }

      @ProcessElement
      public void processElement(ProcessContext context) {
        IngestionPipelineOptions options =
            context.getPipelineOptions().as(IngestionPipelineOptions.class);
        PartitionQueryRequest request =
            PartitionQueryRequest.newBuilder()
                .setPartitionCount(options.getPartitionCount())
                .setParent(getParentPath(options.getProject()))
                .setStructuredQuery(context.element())
                .build();
        PartitionQueryPagedResponse response = client.partitionQuery(request);
        Iterator<Cursor> iterator = response.iterateAll().iterator();

        // Return a Cursor pair to represent the start and end points within which to run the query.
        Cursor start = null;
        Cursor end;
        while (iterator.hasNext()) {
          end = iterator.next();
          context.output(ImmutableTriple.of(start, end, context.element()));
          partitionCursors.inc();
          start = end;
        }
        context.output(ImmutableTriple.of(start, null, context.element()));
        partitionCursors.inc();
      }

      @FinishBundle
      public void finishBundle() {
        shutdownFirestoreClient(client);
      }
    }

    static class ReadFn extends DoFn<ImmutableTriple<Cursor, Cursor, StructuredQuery>, Document> {

      private FirestoreClient client;

      @StartBundle
      public void startBundle() throws Exception {
        client = getFirestoreClient();
      }

      @ProcessElement
      public void processElement(ProcessContext context) {
        IngestionPipelineOptions options =
            context.getPipelineOptions().as(IngestionPipelineOptions.class);
        for (Document doc :
            readDocumentsFromFirestore(client, options.getProject(), context.element())) {
          context.output(doc);
        }
      }

      @FinishBundle
      public void finishBundle() {
        shutdownFirestoreClient(client);
      }
    }
  }

  /** Deletes documents from Firestore. */
  public static final class FirestoreDeleter extends PTransform<PCollection<Document>, PDone> {

    @Override
    public PDone expand(PCollection<Document> input) {
      IngestionPipelineOptions options =
          input.getPipeline().getOptions().as(IngestionPipelineOptions.class);
      Long deleteBatchSize = options.getDeleteBatchSize();
      PCollection<KV<Long, Iterable<Document>>> documentsGroupedIntoBatches =
          input
              // Mapping each document to a random long key between 0 and deleteBatchSize will help
              // us asynchronously form batches of documents to delete. Assigning a key randomly to
              // each document will roughly uniformly distribute documents across the buckets. This
              // way we can form multiple batches at a time and run multiple batch deletes at a
              // time.
              .apply(
                  "AddRandomLongAsKey",
                  MapElements.via(
                      new SimpleFunction<Document, KV<Long, Document>>() {
                        @Override
                        public KV<Long, Document> apply(Document input) {
                          return KV.of(
                              ThreadLocalRandom.current().nextLong(0L, deleteBatchSize), input);
                        }
                      }))
              .apply("GroupIntoBatches", GroupIntoBatches.ofSize(deleteBatchSize));
      documentsGroupedIntoBatches.apply("BatchDelete", ParDo.of(new DeleteFn()));
      return PDone.in(input.getPipeline());
    }

    static class DeleteFn extends DoFn<KV<Long, Iterable<Document>>, Void> {

      private FirestoreClient client;

      @StartBundle
      public void startBundle() throws Exception {
        client = getFirestoreClient();
      }

      @ProcessElement
      public void processElement(ProcessContext context) {
        IngestionPipelineOptions options =
            context.getPipelineOptions().as(IngestionPipelineOptions.class);
        if (context.element().getValue() != null) {
          BatchWriteRequest.Builder batchDelete =
              BatchWriteRequest.newBuilder()
                  .setDatabase("projects/" + options.getProject() + "/databases/(default)");
          for (Document doc : context.element().getValue()) {
            if (doc.getName() == null) {
              LOG.warn("Attempted to delete Document with null Path");
              continue;
            }
            batchDelete.addWrites(Write.newBuilder().setDelete(doc.getName()).build());
          }
          BatchWriteResponse response = client.batchWrite(batchDelete.build());
          List<Status> deleteResults = response.getStatusList();
          for (int index = 0; index < deleteResults.size(); index++) {
            Status status = deleteResults.get(index);
            if (status.getCode() != Code.OK.getNumber()) {
              failedDeletes.inc();
              LOG.warn(
                  "Failed to delete doc at: %s with Code: %s and Message: %s",
                  batchDelete.getWrites(index).getDelete(), status.getCode(), status.getMessage());
            } else {
              documentsDeleted.inc();
            }
          }
        }
      }

      @FinishBundle
      public void finishBundle() {
        shutdownFirestoreClient(client);
      }
    }
  }

  // Returns a v1.Firestore instance to be used to partition read queries.
  private static FirestoreClient getFirestoreClient() throws IOException {
    FirestoreSettings settings =
        FirestoreSettings.newBuilder()
            .setCredentialsProvider(
                FixedCredentialsProvider.create(GoogleCredentials.getApplicationDefault()))
            .build();
    return FirestoreClient.create(settings);
  }

  public static void shutdownFirestoreClient(FirestoreClient client) {
    client.shutdown();
    LOG.info("Waiting for FirestoreClient to shutdown.");
    try {
      client.awaitTermination(FIRESTORE_WAIT_TIME.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      LOG.warn("Interrupted while waiting for client shutdown", e);
    }
  }

  private static String getParentPath(String projectId) {
    return "projects/" + projectId + "/databases/(default)/documents";
  }

  // Formats a time given in epoch seconds in the format: yyyy-MM-dd-HH
  public static String formatDateTime(Long time) {
    LocalDateTime dateTimeToQuery = LocalDateTime.ofEpochSecond(time, 0, ZoneOffset.UTC);
    // Reformat the date to mirror the format of documents in Firestore: yyyy-MM-dd-HH.
    DateTimeFormatter formatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HH", Locale.US).withZone(ZoneOffset.UTC);
    return formatter.format(dateTimeToQuery);
  }

  // Returns a list of Documents captured within the given query Cursor pair.
  private static List<Document> readDocumentsFromFirestore(
      FirestoreClient firestoreClient,
      String projectId,
      ImmutableTriple<Cursor, Cursor, StructuredQuery> cursors) {
    StructuredQuery.Builder queryBuilder = cursors.getRight().toBuilder();
    if (cursors.getLeft() != null) {
      queryBuilder.setStartAt(cursors.getLeft());
    }
    if (cursors.getMiddle() != null) {
      queryBuilder.setEndAt(cursors.getMiddle());
    }
    List<Document> docs = new ArrayList<>();
    try {
      ServerStream<RunQueryResponse> responseIterator =
          firestoreClient
              .runQueryCallable()
              .call(
                  RunQueryRequest.newBuilder()
                      .setStructuredQuery(queryBuilder.build())
                      .setParent(getParentPath(projectId))
                      .build());
      responseIterator.forEach(
          res -> {
            skippedResults.inc(res.getSkippedResults());
            // Streaming grpc may return partial results
            if (res.hasDocument()) {
              docs.add(res.getDocument());
              LOG.debug("Fetched document from Firestore: " + res.getDocument().getName());
              documentsRead.inc();
            } else {
              partialProgress.inc();
            }
          });
    } catch (StatusRuntimeException e) {
      LOG.warn("grpc status exception", e);
      grpcException.inc();
    }
    return docs;
  }
}
