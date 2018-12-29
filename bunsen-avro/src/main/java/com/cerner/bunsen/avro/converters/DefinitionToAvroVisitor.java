package com.cerner.bunsen.avro.converters;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import com.cerner.bunsen.definitions.ChoiceConverter;
import com.cerner.bunsen.definitions.DefinitionVisitor;
import com.cerner.bunsen.definitions.FhirConversionSupport;
import com.cerner.bunsen.definitions.HapiCompositeConverter;
import com.cerner.bunsen.definitions.HapiConverter;
import com.cerner.bunsen.definitions.HapiConverter.HapiFieldSetter;
import com.cerner.bunsen.definitions.HapiConverter.HapiObjectConverter;
import com.cerner.bunsen.definitions.LeafExtensionConverter;
import com.cerner.bunsen.definitions.PrimitiveConverter;
import com.cerner.bunsen.definitions.StringConverter;
import com.cerner.bunsen.definitions.StructureField;
import com.google.common.collect.ImmutableMap;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.avro.Conversion;
import org.apache.avro.Conversions;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.specific.SpecificData;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

public class DefinitionToAvroVisitor implements DefinitionVisitor<HapiConverter<Schema>> {

  private final FhirConversionSupport fhirSupport;

  private final Map<String, HapiConverter<Schema>> compositeConverters;

  private static final HapiConverter STRING_CONVERTER =
      new StringConverter(Schema.create(Type.STRING));

  private static final HapiConverter DATE_CONVERTER =
      new StringConverter(Schema.create(Type.STRING));

  private static final Schema BOOLEAN_SCHEMA = Schema.create(Type.BOOLEAN);

  private static final HapiConverter BOOLEAN_CONVERTER = new PrimitiveConverter<Schema>() {

    @Override
    public Schema getDataType() {
      return BOOLEAN_SCHEMA;
    }
  };

  private static final Schema INTEGER_SCHEMA = Schema.create(Type.INT);

  private static final HapiConverter INTEGER_CONVERTER = new PrimitiveConverter<Schema>() {

    @Override
    public Schema getDataType() {
      return INTEGER_SCHEMA;
    }
  };

  private static final LogicalTypes.Decimal DECIMAL_PRECISION
      = LogicalTypes.decimal(12, 4);

  private static final Schema DECIMAL_SCHEMA =
      DECIMAL_PRECISION.addToSchema(Schema.create(Type.BYTES));

  private static final HapiConverter DECIMAL_CONVERTER = new PrimitiveConverter<Schema>() {

    private final Conversion<BigDecimal> conversion = new Conversions.DecimalConversion();

    protected void toHapi(Object input, IPrimitiveType primitive) {

      primitive.setValue(input);
    }

    protected Object fromHapi(IPrimitiveType primitive) {

      return primitive.getValue();
    }

    @Override
    public Schema getDataType() {
      return DECIMAL_SCHEMA;
    }
  };

  private static final HapiConverter ENUM_CONVERTER =
      new StringConverter(Schema.create(Type.STRING));

  static final Map<String,HapiConverter> TYPE_TO_CONVERTER =
      ImmutableMap.<String,HapiConverter>builder()
          .put("id", STRING_CONVERTER)
          .put("boolean", BOOLEAN_CONVERTER)
          .put("code", ENUM_CONVERTER)
          .put("markdown", STRING_CONVERTER)
          .put("date", DATE_CONVERTER)
          .put("instant", DATE_CONVERTER)
          .put("datetime", DATE_CONVERTER)
          .put("dateTime", DATE_CONVERTER)
          .put("time", STRING_CONVERTER)
          .put("string", STRING_CONVERTER)
          .put("oid", STRING_CONVERTER)
          .put("xhtml", STRING_CONVERTER)
          .put("decimal", DECIMAL_CONVERTER)
          .put("integer", INTEGER_CONVERTER)
          .put("unsignedInt", INTEGER_CONVERTER)
          .put("positiveInt", INTEGER_CONVERTER)
          .put("base64Binary", STRING_CONVERTER) // FIXME: convert to Base64
          .put("uri", STRING_CONVERTER)
          .build();

  private static class CompositeToAvroConverter extends HapiCompositeConverter<Schema> {

    private final GenericData avroData = SpecificData.get();

    CompositeToAvroConverter(String elementType,
        List<StructureField<HapiConverter<Schema>>> children,
        Schema structType,
        FhirConversionSupport fhirSupport) {
      this(elementType, children, structType, fhirSupport, null);
    }

    CompositeToAvroConverter(String elementType,
        List<StructureField<HapiConverter<Schema>>> children,
        Schema structType,
        FhirConversionSupport fhirSupport,
        String extensionUrl) {

      super(elementType, children, structType, fhirSupport, extensionUrl);
    }

    @Override
    protected Object getChild(Object composite, int index) {

      return ((IndexedRecord) composite).get(index);
    }

    @Override
    protected Object createComposite(Object[] children) {

      IndexedRecord record = (IndexedRecord) avroData.newRecord(null, getDataType());

      for (int i = 0; i < children.length; ++i) {

        record.put(i, children[i]);
      }

      return record;
    }

    @Override
    protected boolean isMultiValued(Schema schema) {

      return schema.getType().equals(Schema.Type.ARRAY);
    }
  }

  private static class ChoiceToAvroConverter extends ChoiceConverter<Schema> {

    private final GenericData avroData = SpecificData.get();

    ChoiceToAvroConverter(Map<String,HapiConverter<Schema>> choiceTypes,
        Schema structType,
        FhirConversionSupport fhirSupport) {

      super(choiceTypes, structType, fhirSupport);
    }

    @Override
    protected Object getChild(Object composite, int index) {

      return ((IndexedRecord) composite).get(index);
    }

    @Override
    protected Object createComposite(Object[] children) {

      IndexedRecord record = (IndexedRecord) avroData.newRecord(null, getDataType());

      for (int i = 0; i < children.length; ++i) {

        record.put(i, children[i]);
      }

      return record;
    }
  }

  private static class MultiValuedToAvroConverter extends HapiConverter<Schema> {

    private class MultiValuedtoHapiConverter implements HapiFieldSetter {

      private final BaseRuntimeElementDefinition elementDefinition;

      private final HapiObjectConverter elementToHapiConverter;

      MultiValuedtoHapiConverter(BaseRuntimeElementDefinition elementDefinition,
          HapiObjectConverter elementToHapiConverter) {
        this.elementDefinition = elementDefinition;
        this.elementToHapiConverter = elementToHapiConverter;
      }

      @Override
      public void setField(IBase parentObject,
          BaseRuntimeChildDefinition fieldToSet,
          Object element) {

        for (Object value: (Iterable) element) {

          Object hapiObject = elementToHapiConverter.toHapi(value);

          fieldToSet.getMutator().addValue(parentObject, (IBase) hapiObject);
        }
      }
    }

    HapiConverter<Schema> elementConverter;

    MultiValuedToAvroConverter(HapiConverter elementConverter) {
      this.elementConverter = elementConverter;
    }

    @Override
    public Object fromHapi(Object input) {

      List list = (List) input;

      return list.stream()
          .map(item -> elementConverter.fromHapi(item))
          .collect(Collectors.toList());
    }

    @Override
    public Schema getDataType() {

      return Schema.createArray(elementConverter.getDataType());
    }

    @Override
    public HapiFieldSetter toHapiConverter(BaseRuntimeElementDefinition... elementDefinitions) {

      BaseRuntimeElementDefinition elementDefinition = elementDefinitions[0];

      HapiObjectConverter rowToHapiConverter = (HapiObjectConverter)
          elementConverter.toHapiConverter(elementDefinition);

      return new MultiValuedtoHapiConverter(elementDefinition, rowToHapiConverter);
    }
  }

  /**
   * Creates a visitor to construct Avro conversion objects.
   *
   * @param fhirSupport support for FHIR conversions.
   * @param compositeConverters a mutable cache of generated converters that may
   *     be reused by types that contain them.
   */
  public DefinitionToAvroVisitor(FhirConversionSupport fhirSupport,
      Map<String,HapiConverter<Schema>> compositeConverters) {

    this.fhirSupport = fhirSupport;
    this.compositeConverters = compositeConverters;
  }

  @Override
  public HapiConverter<Schema> visitPrimitive(String elementName, String primitiveType) {

    return TYPE_TO_CONVERTER.get(primitiveType);
  }

  private static final Schema NULL_SCHEMA = Schema.create(Type.NULL);

  /**
   * Makes a given schema nullable.
   */
  private static Schema nullable(Schema schema) {

    return Schema.createUnion(Arrays.asList(schema, NULL_SCHEMA));
  }

  @Override
  public HapiConverter<Schema> visitComposite(String elementName,
      String elementPath,
      String baseType,
      String elementTypeUrl,
      List<StructureField<HapiConverter<Schema>>> children) {

    String recordName = recordNameFor(elementPath);
    String recordNamespace = namespaceFor(elementTypeUrl);
    String fullName = recordNamespace + "." + recordName;

    HapiConverter<Schema> converter = compositeConverters.get(fullName);

    if (converter == null) {

      List<Field> fields = children.stream()
          .map((StructureField<HapiConverter<Schema>> field) -> {

            String doc = field.extensionUrl() != null
                ? "Extension field for " + field.extensionUrl()
                : "Field for FHIR property " + field.propertyName();

            return new Field(field.fieldName(),
                nullable(field.result().getDataType()),
                doc,
                (Object) null);

          }).collect(Collectors.toList());

      Schema schema = Schema.createRecord(recordName,
          "Structure for FHIR type " + baseType,
          recordNamespace,
          false,
          fields);

      converter = new CompositeToAvroConverter(baseType,
          children, schema, fhirSupport);

      compositeConverters.put(fullName, converter);
    }

    return converter;
  }

  /**
   * Field setter that does nothing for synthetic or unsupported field types.
   */
  private static class NoOpFieldSetter implements HapiFieldSetter,
      HapiObjectConverter {

    @Override
    public void setField(IBase parentObject, BaseRuntimeChildDefinition fieldToSet,
        Object sparkObject) {

    }

    @Override
    public IBase toHapi(Object input) {
      return null;
    }

  }

  private static final HapiFieldSetter NOOP_FIELD_SETTER = new NoOpFieldSetter();

  /**
   * Converter that returns the relative value of a URI type.
   */
  private static class RelativeValueConverter extends HapiConverter<Schema> {

    private final String prefix;

    RelativeValueConverter(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Object fromHapi(Object input) {
      String uri =  ((IPrimitiveType) input).getValueAsString();

      return uri != null && uri.startsWith(prefix)
          ? uri.substring(uri.lastIndexOf('/') + 1)
          : null;
    }

    @Override
    public Schema getDataType() {
      return Schema.create(Type.STRING);
    }

    @Override
    public String getElementType() {

      return "String";
    }

    @Override
    public HapiFieldSetter toHapiConverter(BaseRuntimeElementDefinition... elementDefinitions) {

      // Returns a field setter that does nothing, since this is for a synthetic type-specific
      // reference field, and the value will be set from the primary field.
      return NOOP_FIELD_SETTER;
    }

  }

  private static final Pattern STRUCTURE_URL_PATTERN =
      Pattern.compile("http:\\/\\/hl7.org\\/fhir(\\/.*)?\\/StructureDefinition\\/([^\\/]*)$");

  private static String recordNameFor(String elementPath) {

    return elementPath.replaceAll("\\.", "");
  }

  private static String namespaceFor(String structuctureDefinitionUrl) {

    Matcher matcher = STRUCTURE_URL_PATTERN.matcher(structuctureDefinitionUrl);

    if (matcher.matches()) {

      String profile = matcher.group(1);

      if (profile != null && profile.length() > 0) {

        String subPackage = profile.replaceAll("/", ".");

        return "com.cerner.bunsen.avro" + subPackage;

      } else {
        return "com.cerner.bunsen.avro";
      }

    } else {
      throw new IllegalArgumentException("Unregonized structure definition URL: "
          + structuctureDefinitionUrl);
    }
  }

  @Override
  public HapiConverter<Schema> visitReference(String elementName,
      List<String> referenceTypes,
      List<StructureField<HapiConverter<Schema>>> children) {

    // Generate a record name based on the type of references it can contain.
    String recordName = referenceTypes.stream().collect(Collectors.joining()) + "Reference";

    String recordNamespace = "com.cerner.bunsen.avro"; // FIXME
    String fullName = recordNamespace + "." + recordName;

    HapiConverter<Schema> converter = compositeConverters.get(fullName);

    if (converter == null) {

      // Add direct references
      List<StructureField<HapiConverter<Schema>>> fieldsWithReferences =
          referenceTypes.stream()
              .map(refUri -> {

                String relativeType = refUri.substring(refUri.lastIndexOf('/') + 1);

                return new StructureField<HapiConverter<Schema>>("reference",
                    relativeType + "Id",
                    null,
                    false,
                    new RelativeValueConverter(relativeType));

              }).collect(Collectors.toList());

      fieldsWithReferences.addAll(children);

      List<Field> fields = fieldsWithReferences.stream()
          .map(entry -> new Field(entry.fieldName(),
              nullable(entry.result().getDataType()),
              "Reference field",
              (Object) null))
          .collect(Collectors.toList());

      Schema schema = Schema.createRecord(recordName,
          "Structure for FHIR type " + recordName,
          "com.cerner.bunsen.avro",
          false, fields);

      converter = new CompositeToAvroConverter(null,
          fieldsWithReferences,
          schema,
          fhirSupport);

      compositeConverters.put(fullName, converter);
    }

    return converter;
  }

  @Override
  public HapiConverter<Schema> visitParentExtension(String elementName,
      String extensionUrl,
      List<StructureField<HapiConverter<Schema>>> children) {

    // Ignore extension fields that don't have declared content for now.
    if (children.isEmpty()) {
      return null;
    }


    String recordNamespace = namespaceFor(extensionUrl);

    String localPart = extensionUrl.substring(extensionUrl.lastIndexOf('/') + 1);

    String[] parts = localPart.split("[-|_]");

    String recordName = Arrays.stream(parts).map(part ->
        part.substring(0,1).toUpperCase() + part.substring(1))
        .collect(Collectors.joining());

    String fullName = recordNamespace + "." + recordName;

    HapiConverter<Schema> converter = compositeConverters.get(fullName);

    if (converter == null) {

      List<Field> fields = children.stream()
          .map(entry ->
              new Field(entry.fieldName(),
                  nullable(entry.result().getDataType()),
                  "Doc here",
                  (Object) null))
          .collect(Collectors.toList());

      Schema schema = Schema.createRecord(recordName,
          "Reference type.",
          recordNamespace,
          false, fields);

      converter = new CompositeToAvroConverter(null,
          children,
          schema,
          fhirSupport,
          extensionUrl);

      compositeConverters.put(fullName, converter);
    }

    return converter;
  }

  @Override
  public HapiConverter<Schema> visitLeafExtension(String elementName, String extensionUrl,
      HapiConverter<Schema> element) {

    return new LeafExtensionConverter<Schema>(extensionUrl, element);
  }

  @Override
  public HapiConverter<Schema> visitMultiValued(String elementName,
      HapiConverter<Schema> arrayElement) {

    return new MultiValuedToAvroConverter(arrayElement);
  }

  @Override
  public HapiConverter<Schema> visitChoice(String elementName,
      Map<String, HapiConverter<Schema>> choiceTypes) {

    List<Field> fields = choiceTypes.entrySet().stream()
        .map(entry -> {

          // Ensure first character of the field is lower case.
          String fieldName = Character.toLowerCase(entry.getKey().charAt(0))
              + entry.getKey().substring(1);

          return new Field(fieldName,
              nullable(entry.getValue().getDataType()),
              "Choice field",
              (Object) null);

        })
        .collect(Collectors.toList());

    // TODO: Use path from root to define choice name?
    String recordName = choiceTypes.keySet().stream().collect(Collectors.joining());

    Schema schema = Schema.createRecord("Choice" + recordName,
        "Structure for FHIR choice type ",
        "com.cerner.bunsen.avro",
        false, fields);

    return new ChoiceToAvroConverter(choiceTypes,
        schema,
        fhirSupport);

  }

  @Override
  public int getMaxDepth(String elementTypeUrl, String path) {
    return 1;
  }
}