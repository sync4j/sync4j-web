package com.fathzer.sync4j.web.sync;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fathzer.sync4j.sync.Statistics;

/**
 * Provides JSON serialization and deserialization for {@link Statistics} class.
 */
public final class StatisticsSerializer {
    private static final String SKIPPED_FILES = "skippedFiles";
    private static final String SKIPPED_FOLDERS = "skippedFolders";

    private StatisticsSerializer() {
        // Private constructor to prevent instantiation
    }

    /**
     * JSON serializer for {@link Statistics}.
     */
    public static class Serializer extends JsonSerializer<Statistics> {
        @Override
        public void serialize(Statistics stats, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            
            // Serialize Counter fields
            writeCounter(gen, "preloadedFolders", stats.preloadedFolders());
            writeCounter(gen, "listedFolders", stats.listedFolders());
            writeCounter(gen, "checkedFiles", stats.checkedFiles());
            writeCounter(gen, "deletedFiles", stats.deletedFiles());
            writeCounter(gen, "copiedFiles", stats.copiedFiles());
            writeCounter(gen, "copiedBytes", stats.copiedBytes());
            writeCounter(gen, "deletedFolders", stats.deletedFolders());
            writeCounter(gen, "createdFolders", stats.createdFolders());
            
            // Serialize AtomicLong fields
            writeIfNotZero(gen, SKIPPED_FILES, stats.skippedFiles().get());
            writeIfNotZero(gen, SKIPPED_FOLDERS, stats.skippedFolders().get());
            
            gen.writeEndObject();
        }
        
        private void writeCounter(JsonGenerator gen, String fieldName, Statistics.Counter counter) throws IOException {
            if (counter.total().get() > 0 || counter.done().get() > 0) {
                gen.writeObjectFieldStart(fieldName);
                gen.writeNumberField("total", counter.total().get());
                gen.writeNumberField("done", counter.done().get());
                gen.writeEndObject();
            }
        }
        
        private void writeIfNotZero(JsonGenerator gen, String fieldName, long value) throws IOException {
            if (value > 0) {
                gen.writeNumberField(fieldName, value);
            }
        }
    }

    /**
     * JSON deserializer for {@link Statistics}.
     */
    public static class Deserializer extends JsonDeserializer<Statistics> {
        @Override
        public Statistics deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            Statistics stats = new Statistics();
            
            // Deserialize Counter fields
            deserializeCounter(node, "preloadedFolders", stats.preloadedFolders());
            deserializeCounter(node, "listedFolders", stats.listedFolders());
            deserializeCounter(node, "checkedFiles", stats.checkedFiles());
            deserializeCounter(node, "deletedFiles", stats.deletedFiles());
            deserializeCounter(node, "copiedFiles", stats.copiedFiles());
            deserializeCounter(node, "copiedBytes", stats.copiedBytes());
            deserializeCounter(node, "deletedFolders", stats.deletedFolders());
            deserializeCounter(node, "createdFolders", stats.createdFolders());
            
            // Deserialize AtomicLong fields
            if (node.has(SKIPPED_FILES)) {
                stats.skippedFiles().set(node.get(SKIPPED_FILES).asLong());
            }
            if (node.has(SKIPPED_FOLDERS)) {
                stats.skippedFolders().set(node.get(SKIPPED_FOLDERS).asLong());
            }
            
            return stats;
        }
        
        private void deserializeCounter(JsonNode node, String fieldName, Statistics.Counter counter) {
            if (node.has(fieldName)) {
                JsonNode counterNode = node.get(fieldName);
                counter.total().set(counterNode.path("total").asLong());
                counter.done().set(counterNode.path("done").asLong());
            }
        }
    }
    
    /**
     * Returns a Jackson Module that registers the Statistics serializer and deserializer.
     * @return a configured SimpleModule
     */
    public static SimpleModule getModule() {
        SimpleModule module = new SimpleModule("StatisticsModule");
        module.addSerializer(Statistics.class, new Serializer());
        module.addDeserializer(Statistics.class, new Deserializer());
        return module;
    }
}
