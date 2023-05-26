// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: reflection.proto

package otoroshi.next.plugins.grpc;

/**
 * <pre>
 * The error code and error message sent by the server when an error occurs.
 * </pre>
 *
 * Protobuf type {@code grpc.reflection.v1alpha.ErrorResponse}
 */
public final class ErrorResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:grpc.reflection.v1alpha.ErrorResponse)
    ErrorResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use ErrorResponse.newBuilder() to construct.
  private ErrorResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private ErrorResponse() {
    errorMessage_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new ErrorResponse();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return otoroshi.next.plugins.grpc.ServerReflectionProto.internal_static_grpc_reflection_v1alpha_ErrorResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return otoroshi.next.plugins.grpc.ServerReflectionProto.internal_static_grpc_reflection_v1alpha_ErrorResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            otoroshi.next.plugins.grpc.ErrorResponse.class, otoroshi.next.plugins.grpc.ErrorResponse.Builder.class);
  }

  public static final int ERROR_CODE_FIELD_NUMBER = 1;
  private int errorCode_ = 0;
  /**
   * <pre>
   * This field uses the error codes defined in grpc::StatusCode.
   * </pre>
   *
   * <code>int32 error_code = 1;</code>
   * @return The errorCode.
   */
  @java.lang.Override
  public int getErrorCode() {
    return errorCode_;
  }

  public static final int ERROR_MESSAGE_FIELD_NUMBER = 2;
  @SuppressWarnings("serial")
  private volatile java.lang.Object errorMessage_ = "";
  /**
   * <code>string error_message = 2;</code>
   * @return The errorMessage.
   */
  @java.lang.Override
  public java.lang.String getErrorMessage() {
    java.lang.Object ref = errorMessage_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      errorMessage_ = s;
      return s;
    }
  }
  /**
   * <code>string error_message = 2;</code>
   * @return The bytes for errorMessage.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getErrorMessageBytes() {
    java.lang.Object ref = errorMessage_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      errorMessage_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (errorCode_ != 0) {
      output.writeInt32(1, errorCode_);
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(errorMessage_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 2, errorMessage_);
    }
    getUnknownFields().writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (errorCode_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(1, errorCode_);
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(errorMessage_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(2, errorMessage_);
    }
    size += getUnknownFields().getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof otoroshi.next.plugins.grpc.ErrorResponse)) {
      return super.equals(obj);
    }
    otoroshi.next.plugins.grpc.ErrorResponse other = (otoroshi.next.plugins.grpc.ErrorResponse) obj;

    if (getErrorCode()
        != other.getErrorCode()) return false;
    if (!getErrorMessage()
        .equals(other.getErrorMessage())) return false;
    if (!getUnknownFields().equals(other.getUnknownFields())) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    hash = (37 * hash) + ERROR_CODE_FIELD_NUMBER;
    hash = (53 * hash) + getErrorCode();
    hash = (37 * hash) + ERROR_MESSAGE_FIELD_NUMBER;
    hash = (53 * hash) + getErrorMessage().hashCode();
    hash = (29 * hash) + getUnknownFields().hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static otoroshi.next.plugins.grpc.ErrorResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static otoroshi.next.plugins.grpc.ErrorResponse parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(otoroshi.next.plugins.grpc.ErrorResponse prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * The error code and error message sent by the server when an error occurs.
   * </pre>
   *
   * Protobuf type {@code grpc.reflection.v1alpha.ErrorResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:grpc.reflection.v1alpha.ErrorResponse)
      otoroshi.next.plugins.grpc.ErrorResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return otoroshi.next.plugins.grpc.ServerReflectionProto.internal_static_grpc_reflection_v1alpha_ErrorResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return otoroshi.next.plugins.grpc.ServerReflectionProto.internal_static_grpc_reflection_v1alpha_ErrorResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              otoroshi.next.plugins.grpc.ErrorResponse.class, otoroshi.next.plugins.grpc.ErrorResponse.Builder.class);
    }

    // Construct using otoroshi.next.plugins.grpc.ErrorResponse.newBuilder()
    private Builder() {

    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);

    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      bitField0_ = 0;
      errorCode_ = 0;
      errorMessage_ = "";
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return otoroshi.next.plugins.grpc.ServerReflectionProto.internal_static_grpc_reflection_v1alpha_ErrorResponse_descriptor;
    }

    @java.lang.Override
    public otoroshi.next.plugins.grpc.ErrorResponse getDefaultInstanceForType() {
      return otoroshi.next.plugins.grpc.ErrorResponse.getDefaultInstance();
    }

    @java.lang.Override
    public otoroshi.next.plugins.grpc.ErrorResponse build() {
      otoroshi.next.plugins.grpc.ErrorResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public otoroshi.next.plugins.grpc.ErrorResponse buildPartial() {
      otoroshi.next.plugins.grpc.ErrorResponse result = new otoroshi.next.plugins.grpc.ErrorResponse(this);
      if (bitField0_ != 0) { buildPartial0(result); }
      onBuilt();
      return result;
    }

    private void buildPartial0(otoroshi.next.plugins.grpc.ErrorResponse result) {
      int from_bitField0_ = bitField0_;
      if (((from_bitField0_ & 0x00000001) != 0)) {
        result.errorCode_ = errorCode_;
      }
      if (((from_bitField0_ & 0x00000002) != 0)) {
        result.errorMessage_ = errorMessage_;
      }
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof otoroshi.next.plugins.grpc.ErrorResponse) {
        return mergeFrom((otoroshi.next.plugins.grpc.ErrorResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(otoroshi.next.plugins.grpc.ErrorResponse other) {
      if (other == otoroshi.next.plugins.grpc.ErrorResponse.getDefaultInstance()) return this;
      if (other.getErrorCode() != 0) {
        setErrorCode(other.getErrorCode());
      }
      if (!other.getErrorMessage().isEmpty()) {
        errorMessage_ = other.errorMessage_;
        bitField0_ |= 0x00000002;
        onChanged();
      }
      this.mergeUnknownFields(other.getUnknownFields());
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      if (extensionRegistry == null) {
        throw new java.lang.NullPointerException();
      }
      try {
        boolean done = false;
        while (!done) {
          int tag = input.readTag();
          switch (tag) {
            case 0:
              done = true;
              break;
            case 8: {
              errorCode_ = input.readInt32();
              bitField0_ |= 0x00000001;
              break;
            } // case 8
            case 18: {
              errorMessage_ = input.readStringRequireUtf8();
              bitField0_ |= 0x00000002;
              break;
            } // case 18
            default: {
              if (!super.parseUnknownField(input, extensionRegistry, tag)) {
                done = true; // was an endgroup tag
              }
              break;
            } // default:
          } // switch (tag)
        } // while (!done)
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        throw e.unwrapIOException();
      } finally {
        onChanged();
      } // finally
      return this;
    }
    private int bitField0_;

    private int errorCode_ ;
    /**
     * <pre>
     * This field uses the error codes defined in grpc::StatusCode.
     * </pre>
     *
     * <code>int32 error_code = 1;</code>
     * @return The errorCode.
     */
    @java.lang.Override
    public int getErrorCode() {
      return errorCode_;
    }
    /**
     * <pre>
     * This field uses the error codes defined in grpc::StatusCode.
     * </pre>
     *
     * <code>int32 error_code = 1;</code>
     * @param value The errorCode to set.
     * @return This builder for chaining.
     */
    public Builder setErrorCode(int value) {
      
      errorCode_ = value;
      bitField0_ |= 0x00000001;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * This field uses the error codes defined in grpc::StatusCode.
     * </pre>
     *
     * <code>int32 error_code = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearErrorCode() {
      bitField0_ = (bitField0_ & ~0x00000001);
      errorCode_ = 0;
      onChanged();
      return this;
    }

    private java.lang.Object errorMessage_ = "";
    /**
     * <code>string error_message = 2;</code>
     * @return The errorMessage.
     */
    public java.lang.String getErrorMessage() {
      java.lang.Object ref = errorMessage_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        errorMessage_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string error_message = 2;</code>
     * @return The bytes for errorMessage.
     */
    public com.google.protobuf.ByteString
        getErrorMessageBytes() {
      java.lang.Object ref = errorMessage_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        errorMessage_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string error_message = 2;</code>
     * @param value The errorMessage to set.
     * @return This builder for chaining.
     */
    public Builder setErrorMessage(
        java.lang.String value) {
      if (value == null) { throw new NullPointerException(); }
      errorMessage_ = value;
      bitField0_ |= 0x00000002;
      onChanged();
      return this;
    }
    /**
     * <code>string error_message = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearErrorMessage() {
      errorMessage_ = getDefaultInstance().getErrorMessage();
      bitField0_ = (bitField0_ & ~0x00000002);
      onChanged();
      return this;
    }
    /**
     * <code>string error_message = 2;</code>
     * @param value The bytes for errorMessage to set.
     * @return This builder for chaining.
     */
    public Builder setErrorMessageBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) { throw new NullPointerException(); }
      checkByteStringIsUtf8(value);
      errorMessage_ = value;
      bitField0_ |= 0x00000002;
      onChanged();
      return this;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:grpc.reflection.v1alpha.ErrorResponse)
  }

  // @@protoc_insertion_point(class_scope:grpc.reflection.v1alpha.ErrorResponse)
  private static final otoroshi.next.plugins.grpc.ErrorResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new otoroshi.next.plugins.grpc.ErrorResponse();
  }

  public static otoroshi.next.plugins.grpc.ErrorResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<ErrorResponse>
      PARSER = new com.google.protobuf.AbstractParser<ErrorResponse>() {
    @java.lang.Override
    public ErrorResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      Builder builder = newBuilder();
      try {
        builder.mergeFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        throw e.setUnfinishedMessage(builder.buildPartial());
      } catch (com.google.protobuf.UninitializedMessageException e) {
        throw e.asInvalidProtocolBufferException().setUnfinishedMessage(builder.buildPartial());
      } catch (java.io.IOException e) {
        throw new com.google.protobuf.InvalidProtocolBufferException(e)
            .setUnfinishedMessage(builder.buildPartial());
      }
      return builder.buildPartial();
    }
  };

  public static com.google.protobuf.Parser<ErrorResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<ErrorResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public otoroshi.next.plugins.grpc.ErrorResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

