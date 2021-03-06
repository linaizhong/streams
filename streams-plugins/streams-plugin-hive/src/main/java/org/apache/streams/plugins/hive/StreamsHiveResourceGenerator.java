/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.streams.plugins.hive;

import org.apache.streams.util.schema.FieldType;
import org.apache.streams.util.schema.FieldUtil;
import org.apache.streams.util.schema.FileUtil;
import org.apache.streams.util.schema.Schema;
import org.apache.streams.util.schema.SchemaStore;
import org.apache.streams.util.schema.SchemaStoreImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.jsonschema2pojo.util.URLUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.apache.streams.util.schema.FileUtil.dropExtension;
import static org.apache.streams.util.schema.FileUtil.dropSourcePathPrefix;
import static org.apache.streams.util.schema.FileUtil.swapExtension;
import static org.apache.streams.util.schema.FileUtil.writeFile;

/**
 * Generates hive table definitions for using org.openx.data.jsonserde.JsonSerDe on new-line delimited json documents.
 *
 *
 */
public class StreamsHiveResourceGenerator implements Runnable {

  private static final Logger LOGGER = LoggerFactory.getLogger(StreamsHiveResourceGenerator.class);

  private static final String LS = System.getProperty("line.separator");

  private StreamsHiveGenerationConfig config;

  private SchemaStore schemaStore = new SchemaStoreImpl();

  private int currentDepth = 0;

  /**
   * Run from CLI without Maven
   *
   * <p></p>
   * java -jar streams-plugin-hive-jar-with-dependencies.jar StreamsHiveResourceGenerator src/main/jsonschema target/generated-resources
   *
   * @param args [sourceDirectory, targetDirectory]
   * */
  public static void main(String[] args) {
    StreamsHiveGenerationConfig config = new StreamsHiveGenerationConfig();

    String sourceDirectory = "src/main/jsonschema";
    String targetDirectory = "target/generated-resources/hive";

    if ( args.length > 0 ) {
      sourceDirectory = args[0];
    }
    if ( args.length > 1 ) {
      targetDirectory = args[1];
    }

    config.setSourceDirectory(sourceDirectory);
    config.setTargetDirectory(targetDirectory);

    StreamsHiveResourceGenerator streamsHiveResourceGenerator = new StreamsHiveResourceGenerator(config);
    streamsHiveResourceGenerator.run();
  }

  public StreamsHiveResourceGenerator(StreamsHiveGenerationConfig config) {
    this.config = config;
  }

  @Override
  public void run() {

    Objects.requireNonNull(config);

    generate(config);

  }

  /**
   * run generate using supplied StreamsHiveGenerationConfig.
   * @param config StreamsHiveGenerationConfig
   */
  public void generate(StreamsHiveGenerationConfig config) {

    LinkedList<File> sourceFiles = new LinkedList<>();

    for (Iterator<URL> sources = config.getSource(); sources.hasNext();) {
      URL source = sources.next();
      sourceFiles.add(URLUtil.getFileFromURL(source));
    }

    LOGGER.info("Seeded with {} source paths:", sourceFiles.size());

    FileUtil.resolveRecursive(config, sourceFiles);

    LOGGER.info("Resolved {} schema files:", sourceFiles.size());

    for (File item : sourceFiles) {
      schemaStore.create(item.toURI());
    }

    LOGGER.info("Identified {} objects:", schemaStore.getSize());

    for (Iterator<Schema> schemaIterator = schemaStore.getSchemaIterator(); schemaIterator.hasNext(); ) {
      Schema schema = schemaIterator.next();
      currentDepth = 0;
      if ( schema.getUri().getScheme().equals("file")) {
        String inputFile = schema.getUri().getPath();
        String resourcePath = dropSourcePathPrefix(inputFile, config.getSourceDirectory());
        for (String sourcePath : config.getSourcePaths()) {
          resourcePath = dropSourcePathPrefix(resourcePath, sourcePath);
        }
        String outputFile = config.getTargetDirectory() + "/" + swapExtension(resourcePath, "json", "hql");

        LOGGER.info("Processing {}:", resourcePath);

        String resourceId = dropExtension(resourcePath).replace("/", "_");

        String resourceContent = generateResource(schema, resourceId);

        writeFile(outputFile, resourceContent);

        LOGGER.info("Wrote {}:", outputFile);
      }
    }
  }

  /**
   * generateResource String from schema and resourceId.
   * @param schema Schema
   * @param resourceId String
   * @return CREATE TABLE ...
   */
  public String generateResource(Schema schema, String resourceId) {
    StringBuilder resourceBuilder = new StringBuilder();
    resourceBuilder.append("CREATE TABLE ");
    resourceBuilder.append(hqlEscape(resourceId));
    resourceBuilder.append(LS);
    resourceBuilder.append("(");
    resourceBuilder.append(LS);
    resourceBuilder = appendRootObject(resourceBuilder, schema, resourceId, ' ');
    resourceBuilder.append(")");
    resourceBuilder.append(LS);
    resourceBuilder.append("ROW FORMAT SERDE 'org.openx.data.jsonserde.JsonSerDe'");
    resourceBuilder.append(LS);
    resourceBuilder.append("WITH SERDEPROPERTIES (\"ignore.malformed.json\" = \"true\"");
    resourceBuilder.append(LS);
    resourceBuilder.append("STORED AS TEXTFILE");
    resourceBuilder.append(LS);
    resourceBuilder.append("LOCATION '${hiveconf:path}';");
    resourceBuilder.append(LS);
    return resourceBuilder.toString();
  }

  protected StringBuilder appendRootObject(StringBuilder builder, Schema schema, String resourceId, Character seperator) {
    ObjectNode propertiesNode = schemaStore.resolveProperties(schema, null, resourceId);
    if ( propertiesNode != null && propertiesNode.isObject() && propertiesNode.size() > 0) {
      builder = appendPropertiesNode(builder, schema, propertiesNode, seperator);
    }
    return builder;
  }

  private StringBuilder appendValueField(StringBuilder builder, Schema schema, String fieldId, FieldType fieldType, Character seperator) {
    // safe to append nothing
    Objects.requireNonNull(builder);
    builder.append(hqlEscape(fieldId));
    builder.append(seperator);
    builder.append(hqlType(fieldType));
    return builder;
  }

  protected StringBuilder appendArrayItems(StringBuilder builder, Schema schema, String fieldId, ObjectNode itemsNode, Character seperator) {
    // not safe to append nothing
    Objects.requireNonNull(builder);
    if ( itemsNode == null ) {
      return builder;
    }
    if ( itemsNode.has("type")) {
      try {
        FieldType itemType = FieldUtil.determineFieldType(itemsNode);
        switch ( itemType ) {
          case OBJECT:
            builder = appendArrayObject(builder, schema, fieldId, itemsNode, seperator);
            break;
          case ARRAY:
            ObjectNode subArrayItems = (ObjectNode) itemsNode.get("items");
            builder = appendArrayItems(builder, schema, fieldId, subArrayItems, seperator);
            break;
          default:
            builder = appendArrayField(builder, schema, fieldId, itemType, seperator);
        }
      } catch (Exception ex) {
        LOGGER.warn("No item type resolvable for {}", fieldId);
      }
    }
    Objects.requireNonNull(builder);
    return builder;
  }

  private StringBuilder appendArrayField(StringBuilder builder, Schema schema, String fieldId, FieldType fieldType, Character seperator) {
    // safe to append nothing
    Objects.requireNonNull(builder);
    Objects.requireNonNull(fieldId);
    builder.append(hqlEscape(fieldId));
    builder.append(seperator);
    builder.append("ARRAY<" + hqlType(fieldType) + ">");
    Objects.requireNonNull(builder);
    return builder;
  }

  private StringBuilder appendArrayObject(StringBuilder builder, Schema schema, String fieldId, ObjectNode fieldNode, Character seperator) {
    // safe to append nothing
    Objects.requireNonNull(builder);
    Objects.requireNonNull(fieldNode);
    if (StringUtils.isNotBlank(fieldId)) {
      builder.append(hqlEscape(fieldId));
      builder.append(seperator);
    }
    builder.append("ARRAY");
    builder.append(LS);
    builder.append("<");
    builder.append(LS);
    ObjectNode propertiesNode = schemaStore.resolveProperties(schema, fieldNode, fieldId);
    builder = appendStructField(builder, schema, "", propertiesNode, ':');
    builder.append(">");
    Objects.requireNonNull(builder);
    return builder;
  }

  private StringBuilder appendStructField(StringBuilder builder, Schema schema, String fieldId, ObjectNode propertiesNode, Character seperator) {
    // safe to append nothing
    Objects.requireNonNull(builder);
    Objects.requireNonNull(propertiesNode);

    if ( propertiesNode != null && propertiesNode.isObject() && propertiesNode.size() > 0 ) {

      currentDepth += 1;

      if (StringUtils.isNotBlank(fieldId)) {
        builder.append(hqlEscape(fieldId));
        builder.append(seperator);
      }
      builder.append("STRUCT");
      builder.append(LS);
      builder.append("<");
      builder.append(LS);

      builder = appendPropertiesNode(builder, schema, propertiesNode, ':');

      builder.append(">");
      builder.append(LS);

      currentDepth -= 1;

    }
    Objects.requireNonNull(builder);
    return builder;
  }

  private StringBuilder appendPropertiesNode(StringBuilder builder, Schema schema, ObjectNode propertiesNode, Character seperator) {
    Objects.requireNonNull(builder);
    Objects.requireNonNull(propertiesNode);
    Iterator<Map.Entry<String, JsonNode>> fields = propertiesNode.fields();
    List<String> fieldStrings = new ArrayList<>();
    for ( ; fields.hasNext(); ) {
      Map.Entry<String, JsonNode> field = fields.next();
      String fieldId = field.getKey();
      if ( !config.getExclusions().contains(fieldId) && field.getValue().isObject()) {
        ObjectNode fieldNode = (ObjectNode) field.getValue();
        FieldType fieldType = FieldUtil.determineFieldType(fieldNode);
        if (fieldType != null ) {
          switch (fieldType) {
            case ARRAY:
              ObjectNode itemsNode = (ObjectNode) fieldNode.get("items");
              if ( currentDepth <= config.getMaxDepth()) {
                StringBuilder arrayItemsBuilder = appendArrayItems(new StringBuilder(), schema, fieldId, itemsNode, seperator);
                if (StringUtils.isNotBlank(arrayItemsBuilder.toString())) {
                  fieldStrings.add(arrayItemsBuilder.toString());
                }
              }
              break;
            case OBJECT:
              ObjectNode childProperties = schemaStore.resolveProperties(schema, fieldNode, fieldId);
              if ( currentDepth < config.getMaxDepth()) {
                StringBuilder structFieldBuilder = appendStructField(new StringBuilder(), schema, fieldId, childProperties, seperator);
                if (StringUtils.isNotBlank(structFieldBuilder.toString())) {
                  fieldStrings.add(structFieldBuilder.toString());
                }
              }
              break;
            default:
              StringBuilder valueFieldBuilder = appendValueField(new StringBuilder(), schema, fieldId, fieldType, seperator);
              if (StringUtils.isNotBlank(valueFieldBuilder.toString())) {
                fieldStrings.add(valueFieldBuilder.toString());
              }
          }
        }
      }
    }
    builder.append(String.join("," + LS, fieldStrings)).append(LS);
    Objects.requireNonNull(builder);
    return builder;
  }

  private static String hqlEscape( String fieldId ) {
    return "`" + fieldId + "`";
  }

  private static String hqlType( FieldType fieldType ) {
    switch ( fieldType ) {
      case INTEGER:
        return "INT";
      case NUMBER:
        return "FLOAT";
      case OBJECT:
        return "STRUCT";
      default:
        return fieldType.name().toUpperCase();
    }
  }

}
