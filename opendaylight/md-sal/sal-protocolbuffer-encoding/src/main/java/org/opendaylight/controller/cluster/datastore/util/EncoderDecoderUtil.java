package org.opendaylight.controller.cluster.datastore.util;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.opendaylight.controller.cluster.datastore.common.SimpleNormalizedNodeMessage;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.impl.codec.xml.XmlDocumentUtils;
import org.opendaylight.yangtools.yang.data.impl.schema.transform.dom.DomUtils;
import org.opendaylight.yangtools.yang.data.impl.schema.transform.dom.parser.DomToNormalizedNodeParserFactory;
import org.opendaylight.yangtools.yang.data.impl.schema.transform.dom.serializer.DomFromNormalizedNodeSerializerFactory;
import org.opendaylight.yangtools.yang.model.api.ChoiceNode;
import org.opendaylight.yangtools.yang.model.api.ContainerSchemaNode;
import org.opendaylight.yangtools.yang.model.api.DataNodeContainer;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
/*
 *
 * <code>EncoderDecoderUtil</code> helps in wrapping the NormalizedNode into a SimpleNormalizedNode
 * protobuf message containing the XML representation of the NormalizeNode
 *
 * @author: syedbahm
 */
public class EncoderDecoderUtil {
  static DocumentBuilderFactory factory;
  static {
    factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setCoalescing(true);
    factory.setIgnoringElementContentWhitespace(true);
    factory.setIgnoringComments(true);
  }

  private static DataSchemaNode findChildNode(Set<DataSchemaNode> children,
      String name) {
    List<DataNodeContainer> containers = Lists.newArrayList();

    for (DataSchemaNode dataSchemaNode : children) {
      if (dataSchemaNode.getQName().getLocalName().equals(name))
        return dataSchemaNode;
      if (dataSchemaNode instanceof DataNodeContainer) {
        containers.add((DataNodeContainer) dataSchemaNode);
      } else if (dataSchemaNode instanceof ChoiceNode) {
        containers.addAll(((ChoiceNode) dataSchemaNode).getCases());
      }
    }

    for (DataNodeContainer container : containers) {
      DataSchemaNode retVal = findChildNode(container.getChildNodes(), name);
      if (retVal != null) {
        return retVal;
      }
    }

    return null;
  }

  private static DataSchemaNode getSchemaNode(SchemaContext context, QName qname) {

    for (Module module : context.findModuleByNamespace(qname.getNamespace())) {
      // we will take the first child as the start of the
      if (module.getChildNodes() != null || !module.getChildNodes().isEmpty()) {

        DataSchemaNode found =
            findChildNode(module.getChildNodes(), qname.getLocalName());
        return found;
      }
    }
    return null;
  }

  private static String toString(Element xml) {
    try {
      Transformer transformer =
          TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");

      StreamResult result = new StreamResult(new StringWriter());
      DOMSource source = new DOMSource(xml);
      transformer.transform(source, result);

      return result.getWriter().toString();
    } catch (IllegalArgumentException | TransformerFactoryConfigurationError
        | TransformerException e) {
      throw new RuntimeException("Unable to serialize xml element " + xml, e);
    }
  }

  /**
   * Helps in generation of NormalizedNodeXml message for the supplied NormalizedNode
   *
   * @param sc --SchemaContext
   * @param normalizedNode -- Normalized Node to be encoded
   * @return SimpleNormalizedNodeMessage.NormalizedNodeXml
   */
  public static SimpleNormalizedNodeMessage.NormalizedNodeXml encode(
      SchemaContext sc, NormalizedNode<?, ?> normalizedNode) {
    Preconditions.checkArgument(sc != null, "Schema context found null");
    Preconditions.checkArgument(normalizedNode != null,
        "normalized node found null");
    ContainerSchemaNode containerNode =
        (ContainerSchemaNode) getSchemaNode(sc, normalizedNode.getIdentifier()
            .getNodeType());
    Preconditions.checkState(containerNode != null,
        "Couldn't find schema node for " + normalizedNode.getIdentifier());
    Iterable<Element> els =
        DomFromNormalizedNodeSerializerFactory
            .getInstance(XmlDocumentUtils.getDocument(),
                DomUtils.defaultValueCodecProvider())
            .getContainerNodeSerializer()
            .serialize(containerNode, (ContainerNode) normalizedNode);
    String xmlString = toString(els.iterator().next());
    SimpleNormalizedNodeMessage.NormalizedNodeXml.Builder builder =
        SimpleNormalizedNodeMessage.NormalizedNodeXml.newBuilder();
    builder.setXmlString(xmlString);
    builder.setNodeIdentifier(((ContainerNode) normalizedNode).getIdentifier()
        .getNodeType().toString());
    return builder.build();

  }

  /**
   * Utilizes the SimpleNormalizedNodeMessage.NormalizedNodeXml to convert into NormalizedNode
   *
   * @param sc -- schema context
   * @param normalizedNodeXml -- containing the normalized Node XML
   * @return NormalizedNode return
   * @throws Exception
   */

  public static NormalizedNode decode(SchemaContext sc,
      SimpleNormalizedNodeMessage.NormalizedNodeXml normalizedNodeXml)
      throws Exception {
    Preconditions.checkArgument(sc != null, "schema context seems to be null");
    Preconditions.checkArgument(normalizedNodeXml != null,
        "SimpleNormalizedNodeMessage.NormalizedNodeXml found to be null");
    QName qname = QName.create(normalizedNodeXml.getNodeIdentifier());

    // here we will try to get back the NormalizedNode
    ContainerSchemaNode containerSchemaNode =
        (ContainerSchemaNode) getSchemaNode(sc, qname);

    // now we need to read the XML

    Document doc =
        factory.newDocumentBuilder().parse(
            new ByteArrayInputStream(normalizedNodeXml.getXmlString().getBytes(
                "utf-8")));
    doc.getDocumentElement().normalize();

    ContainerNode result =
        DomToNormalizedNodeParserFactory
            .getInstance(DomUtils.defaultValueCodecProvider())
            .getContainerNodeParser()
            .parse(Collections.singletonList(doc.getDocumentElement()),
                containerSchemaNode);

    return (NormalizedNode) result;

  }



}