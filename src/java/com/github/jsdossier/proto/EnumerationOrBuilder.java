// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: dossier.proto

package com.github.jsdossier.proto;

public interface EnumerationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:dossier.Enumeration)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>optional .dossier.Comment type = 1;</code>
   */
  boolean hasType();
  /**
   * <code>optional .dossier.Comment type = 1;</code>
   */
  com.github.jsdossier.proto.Comment getType();
  /**
   * <code>optional .dossier.Comment type = 1;</code>
   */
  com.github.jsdossier.proto.CommentOrBuilder getTypeOrBuilder();

  /**
   * <code>repeated .dossier.Enumeration.Value value = 2;</code>
   */
  java.util.List<com.github.jsdossier.proto.Enumeration.Value> 
      getValueList();
  /**
   * <code>repeated .dossier.Enumeration.Value value = 2;</code>
   */
  com.github.jsdossier.proto.Enumeration.Value getValue(int index);
  /**
   * <code>repeated .dossier.Enumeration.Value value = 2;</code>
   */
  int getValueCount();
  /**
   * <code>repeated .dossier.Enumeration.Value value = 2;</code>
   */
  java.util.List<? extends com.github.jsdossier.proto.Enumeration.ValueOrBuilder> 
      getValueOrBuilderList();
  /**
   * <code>repeated .dossier.Enumeration.Value value = 2;</code>
   */
  com.github.jsdossier.proto.Enumeration.ValueOrBuilder getValueOrBuilder(
      int index);

  /**
   * <code>optional .dossier.Visibility visibility = 3;</code>
   */
  int getVisibilityValue();
  /**
   * <code>optional .dossier.Visibility visibility = 3;</code>
   */
  com.github.jsdossier.proto.Visibility getVisibility();
}
