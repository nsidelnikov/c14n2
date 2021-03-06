package ru.relex.c14n2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.xml.utils.ObjectVector;
import org.apache.xpath.compiler.XPathParser;
import org.apache.xpath.objects.XString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Node;
import org.w3c.dom.NamedNodeMap;
import org.apache.commons.lang3.StringUtils;

/**
 * C14N2 canonicalizer.
 */
class DOMCanonicalizerHandler {
  private static final Logger LOGGER = LoggerFactory
      .getLogger(DOMCanonicalizerHandler.class);

  private static final String DEFAULT_NS = "";
  private static final String NS = "xmlns";
  private static final String XML = "xml";
  private static final String XSD = "xsd";

  private static final String CF = "&#x%s;";
  private static final String C = ":";

  private static final byte[] XD = {'&','#','x','D',';'};
  private static final byte[] GT = {'&','g','t',';'};
  private static final byte[] LT = {'&','l','t',';'};
  private static final byte[] AMP = {'&','a','m','p',';'};

  private List<Node> excludeList;
  private Parameters parameters;
  private StringBuffer outputBuffer;

  private boolean bStart = true;
  private boolean bEnd = false;

  private Map<String, List<NamespaceContextParams>> namespaces;
  private Map<String, String> sequentialUriMap = new HashMap<String, String>();
  private boolean bSequential = false;

  private Map<String, NSContext> xpathesNsMap = new HashMap<String, NSContext>();

  /**
   * Constructor.
   * 
   * @param parameters
   *          canonicalization parameters
   * @param excludeList
   *          inclusion list
   * @param outputBuffer
   *          output
   */
  protected DOMCanonicalizerHandler(Parameters parameters,
      List<Node> excludeList, StringBuffer outputBuffer) {
    this.parameters = parameters;
    this.outputBuffer = outputBuffer;
    this.excludeList = excludeList;
    bSequential = parameters.getPrefixRewrite().equals(Parameters.SEQUENTIAL);

    namespaces = new HashMap<String, List<NamespaceContextParams>>();

    List<NamespaceContextParams> lst = new ArrayList<NamespaceContextParams>();
    NamespaceContextParams ncp = new NamespaceContextParams();
    if (bSequential) {
      ncp.setNewPrefix(String.format("n%s", 0));
      ncp.setHasOutput(false);
    }
    lst.add(ncp);
    namespaces.put(DEFAULT_NS, lst);

    bStart = true;
    bEnd = false;
  }

  /**
   * Prosessing of element node.
   * 
   * @param node
   *          element node
   */
  protected void processElement(Node node) {
    LOGGER.debug("processElement: {}", node);

    if (isInExcludeList(node))
      return;

    if (getNodeDepth(node) == 1) {
      bStart = false;
    }

    List<NamespaceContextParams> outNSList = processNamespaces(node);

    StringBuffer output = new StringBuffer();
    String prfx = getNodePrefix(node);
    NamespaceContextParams ncp = getLastElement(prfx);
    String localName = getLocalName(node);
    if (namespaces.containsKey(prfx) && !ncp.getNewPrefix().isEmpty()) {
      output.append(String.format("<%s:%s", ncp.getNewPrefix(), localName));
    } else {
      output.append(String.format("<%s", localName));
    }

    List<Attribute> outAttrsList = processAttributes(node);

    for (int i = outNSList.size() - 1; i > 0; i--) {
      NamespaceContextParams ncp1 = outNSList.get(i);
      for (int j = 0; j < i; j++) {
        NamespaceContextParams ncp2 = outNSList.get(j);
        if (ncp1.getNewPrefix().equals(ncp2.getNewPrefix())
            && ncp1.getUri().equals(ncp2.getUri())) {
          outNSList.remove(i);
          break;
        }
      }
    }

    for (NamespaceContextParams namespace : outNSList) {
      if ((prfx.equals(namespace.getPrefix()) && !ncp.getNewPrefix().equals(
          namespace.getNewPrefix()))
          || outputNSInParent(namespace.getPrefix())) {
        ncp.setHasOutput(false);
        continue;
      }
      ncp.setHasOutput(true);
      String nsName = namespace.getNewPrefix();
      String nsUri = namespace.getUri();
      if (!nsName.equals(DEFAULT_NS)) {
        output.append(String.format(" %s:%s=\"%s\"", NS, nsName, nsUri));
      } else {
        output.append(String.format(" %s=\"%s\"", NS, nsUri));
      }
    }

    for (Attribute attribute : outAttrsList) {
      String attrPrfx = attribute.getPrefix();
      String attrName = attribute.getLocalName();
      String attrValue = attribute.getValue();
      if (!bSequential) {
        if (!attrPrfx.equals(DEFAULT_NS)) {
          output.append(String.format(" %s:%s=\"%s\"", attrPrfx, attrName,
              attrValue));
        } else {
          output.append(String.format(" %s=\"%s\"", attrName, attrValue));
        }
      } else {
        if (parameters.getQnameAwareAttributes().size() > 0) {
          if (namespaces.containsKey(attrPrfx)) {
            NamespaceContextParams attrPrfxNcp = getLastElement(attrPrfx);
            for (QNameAwareParameter en : parameters.getQnameAwareAttributes()) {
              if (attrName.equals(en.getName())
                  && en.getNs().equals(attrPrfxNcp.getUri())) {
                int idx = attrValue.indexOf(C);
                if (idx > -1) {
                  String attr_value_prfx = attrValue.substring(0, idx);
                  if (namespaces.containsKey(attr_value_prfx)) {
                    attrValue = getLastElement(attr_value_prfx).getNewPrefix()
                        + C + attrValue.substring(idx + 1);
                  }
                }
              }
            }
          }
        }
        String attrNewPrfx = attribute.getNewPrefix();
        if (!attrPrfx.equals("")) {
          output.append(String.format(" %s:%s=\"%s\"", attrNewPrfx, attrName,
              attrValue));
        } else {
          output.append(String.format(" %s=\"%s\"", attrName, attrValue));
        }
      }
    }

    output.append(">");

    StringUtils.join(outputBuffer,output);
  }

  /**
   * Completion of processing element node.
   * 
   * @param node
   *          element node
   */
  protected void processEndElement(Node node) {
    if (isInExcludeList(node))
      return;

    StringBuffer output = new StringBuffer();
    String prfx = getNodePrefix(node);
    NamespaceContextParams ncp = getLastElement(prfx);
    String localName = getLocalName(node);
    if (namespaces.containsKey(prfx) && !ncp.getNewPrefix().isEmpty()) {
      output.append(String.format("</%s:%s>", ncp.getNewPrefix(), localName));
    } else {
      output.append(String.format("</%s>", localName));
    }

    removeNamespaces(node);

    if (getNodeDepth(node) == 1) {
      bEnd = true;
    }

    StringUtils.join(outputBuffer,output);
  }

  /**
   * Prosessing of text node.
   * 
   * @param node
   *          text node
   */

  protected void processText(Node node) {
    LOGGER.debug("processText: {}", node);
    if (getNodeDepth(node) < 2) {
      return;
    }

    String text = node.getNodeValue() != null ? node.getNodeValue() : "";

    StringBuffer value = new StringBuffer(text.length());
    for (int i = 0; i < text.length(); i++) {
      char codepoint = text.charAt(i);
      if (codepoint == '&') {
        value.append(AMP);
      }
      else if (codepoint == '<') {
        value.append(LT);
      }
      else if (codepoint == '>') {
        value.append(GT);
      }
      else if (codepoint == 0xd) {
        value.append(XD);
      }
      else {
        value.append(codepoint);
      }
    }
    text = value.toString();

    if (parameters.isTrimTextNodes()) {
      boolean b = true;
      NamedNodeMap attrs = node.getParentNode().getAttributes();
      for (int ai = 0; ai < attrs.getLength(); ai++) {
        Node attr = attrs.item(ai);
        if (isInExcludeList(attr))
          continue;
        if (XML.equals(getNodePrefix(attr))
            && "preserve".equals(attr.getNodeValue())
            && getLocalName(attr).equals("space")) {
          b = false;
          break;
        }
      }
      if (b) {
        text = StringUtils.trim(text);
      }
    }

    if (parameters.getQnameAwareElements().size() > 0 && bSequential) {
      if (text.startsWith(XSD + C)) {
        if (namespaces.containsKey(XSD)) {
          Node prntNode = node.getParentNode();
          String nodeName = getLocalName(prntNode);
          String nodePrefix = getNodePrefix(prntNode);
          NamespaceContextParams ncp = getLastElement(XSD);
          NamespaceContextParams attrPrfxNcp = getLastElement(nodePrefix);
          for (QNameAwareParameter en : parameters.getQnameAwareElements()) {
            if (nodeName.equals(en.getName())
                && en.getNs().equals(attrPrfxNcp.getUri())) {
              text = StringUtils.join(ncp.getNewPrefix(), StringUtils.substring(text, XSD.length()));
            }
          }
        }
      }
    }
    if (parameters.getQnameAwareXPathElements().size() > 0 && bSequential
        && node.getParentNode().getChildNodes().getLength() == 1) {
      Node prntNode = node.getParentNode();
      String nodeName = getLocalName(prntNode);
      String nodePrefix = getNodePrefix(prntNode);
      String nodeText = node.getTextContent();
      NamespaceContextParams ncp = getLastElement(nodePrefix);
      for (QNameAwareParameter en : parameters.getQnameAwareXPathElements()) {
        if (nodeName.equals(en.getName()) && ncp.getUri().equals(en.getNs())) {
          NSContext nsContext = xpathesNsMap.get(nodeText);
          List<String> xpathNs = nsContext.getXpathNs();
          StringBuffer sb = new StringBuffer(nodeText.length());
          int baseTextIdx = 0;
          if (xpathNs.size() > 0) {
            Iterator<String> it = xpathNs.iterator();
            String ns = it.next();
            ObjectVector words = nsContext.getWords();
            for (int i = 0; i < words.size(); i++) {
              Object obj = words.elementAt(i);
              String word = obj.toString();
              int idx = StringUtils.indexOf(nodeText, word, baseTextIdx);
              if (idx != baseTextIdx) {
                sb.append(StringUtils.substring(nodeText, baseTextIdx, idx));
                baseTextIdx = idx;
              }
              if (!(obj instanceof XString)
                  && ns.equals(word)
                  && (i != words.size() - 1 && C.equals(words.elementAt(i + 1)))) {
                sb.append(getLastElement(word).getNewPrefix());
                baseTextIdx += word.length();
                if (it.hasNext())
                  ns = it.next();
                else {
                  sb.append(StringUtils.substring(nodeText, baseTextIdx));
                  break;
                }
              } else {
                sb.append(word);
                baseTextIdx += word.length();
              }
            }
            text = sb.toString();
          }
        }
      }
    }

    StringUtils.join(outputBuffer,text);
  }

  /**
   * Prosessing of process instruction node.
   * 
   * @param node
   *          process instruction node
   */
  protected void processPI(Node node) {
    LOGGER.debug("processPI: {}", node);
    String nodeName = node.getNodeName();
    String nodeValue = node.getNodeValue() != null ? node.getNodeValue() : "";

    StringBuffer output = new StringBuffer();
    if (bEnd && getNodeDepth(node) == 1) {
      output.append("\n");
    }
    output.append(String.format("<?%s%s?>", nodeName,
        !nodeValue.isEmpty() ? (" " + nodeValue) : ""));
    if (bStart && getNodeDepth(node) == 1) {
      output.append("\n");
    }
    StringUtils.join(outputBuffer,output);
  }

  /**
   * Prosessing of comment node.
   * 
   * @param node
   *          comment node
   */
  protected void processComment(Node node) {
    LOGGER.debug("processComment: {}", node);
    if (parameters.isIgnoreComments())
      return;

    StringBuffer output = new StringBuffer();
    if (bEnd && getNodeDepth(node) == 1) {
      output.append("\n");
    }
    output.append(String.format("<!--%s-->", node.getNodeValue()));
    if (bStart && getNodeDepth(node) == 1) {
      output.append("\n");
    }
    StringUtils.join(outputBuffer,output);

  }

  /**
   * Prosessing of CDATA node.
   * 
   * @param node
   *          CDATA node
   */
  protected void processCData(Node node) {
    LOGGER.debug("processCData:" + node);
    StringUtils.join(outputBuffer,processText(node.getNodeValue(), false));

  }

  /**
   * Returns an output buffer.
   * 
   * @return Returns an output buffer
   */
  protected StringBuffer getOutputBlock() {
    return outputBuffer;
  }

  /**
   * Returns whether a node in the exclusion list.
   * 
   * @param node
   *          DOM node
   * 
   * @return Returns true if a node there is in exclusion list, false -
   *         otherwise
   */
  protected boolean isInExcludeList(Node node) {
    if (excludeList != null
        && excludeList.contains(node)
        && (node.getNodeType() == Node.ELEMENT_NODE || node instanceof Attr)
        && !(node instanceof Attr && (NS.equals(getNodePrefix(node)) || XML
            .equals(getNodePrefix(node)))))
      return true;
    return false;
  }

  /**
   * Returns a depth of a node in the DOM tree.
   * 
   * @param node
   *          DOM node
   * 
   * @return Returns a depth
   */
  protected int getNodeDepth(Node node) {
    int i = -1;
    Node prnt = node;
    do {
      i++;
      prnt = prnt.getParentNode();
    } while (prnt != null);
    return i;
  }

  /**
   * Returns whether there is a prefix in the parent output.
   * 
   * @param prfx
   *          prefix
   * 
   * @return Returns true if a prefix there is in parent output, false -
   *         otherwise
   */
  private boolean outputNSInParent(String prfx) {
    for (Entry<String, List<NamespaceContextParams>> en : namespaces.entrySet()) {
      if (!bSequential && !prfx.equals(en.getKey()))
        continue;
      List<NamespaceContextParams> lst = en.getValue();
      if (lst.size() > 1) {
        NamespaceContextParams last = getLastElement(prfx);
        for (int i = 2; i <= lst.size(); i++) {
          NamespaceContextParams prev = getLastElement(en.getKey(), -i);
          if (last.getNewPrefix().equals(prev.getNewPrefix())) {
            if (!bSequential && !last.getUri().equals(prev.getUri()))
              return false;
            else if (prev.isHasOutput() == null || prev.isHasOutput())
              return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Remove unused namespaces from the stack.
   * 
   * @param node
   *          DOM node
   */
  private void removeNamespaces(Node node) {
    int nDepth = getNodeDepth(node);
    for (Iterator<Map.Entry<String, List<NamespaceContextParams>>> it = namespaces.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, List<NamespaceContextParams>> entry = it.next();
      List<NamespaceContextParams> nsLevels = entry.getValue();
      while (!nsLevels.isEmpty() &&
             nsLevels.get(nsLevels.size() - 1).getDepth() >= nDepth) {

        nsLevels.remove(nsLevels.size() - 1);
      }
      if (nsLevels.isEmpty()) {
        it.remove();
      }
    }
  }

  /**
   * Prosessing of node attributes.
   * 
   * @param node
   *          DOM node
   * 
   * @return Returns a list of output attributes
   */
  private List<Attribute> processAttributes(final Node node) {
    List<Attribute> outAttrsList = new ArrayList<Attribute>();

    for (int ai = 0; ai < node.getAttributes().getLength(); ai++) {
      Node attr = node.getAttributes().item(ai);
      if (isInExcludeList(attr))
        continue;

      String prfx = getNodePrefix(attr);
      String localName = getLocalName(attr);
      if (!NS.equals(prfx)
          && !(DEFAULT_NS.equals(prfx) && NS.equals(attr.getNodeName()))) {
        Attribute attribute = new Attribute();
        attribute.setPrefix(prfx);
        attribute.setLocalName(localName);
        attribute.setValue(attr.getNodeValue() != null ? attr.getNodeValue()
            : "");
        if (!attribute.getPrefix().isEmpty()
            && namespaces.containsKey(attribute.getPrefix())) {
          attribute.setNewPrefix(getLastElement(attribute.getPrefix())
              .getNewPrefix());
        } else {
          attribute.setNewPrefix(attribute.getPrefix());
        }

        attribute.setValue(processText(attribute.getValue(), true));
        StringBuffer value = new StringBuffer();
        for (int i = 0; i < attribute.getValue().length(); i++) {
          char codepoint = attribute.getValue().charAt(i);
          if (codepoint == 9 || codepoint == 10 || codepoint == 13) {
            value.append(String.format(CF, Integer.toHexString(codepoint)
                .toUpperCase()));
          } else {
            value.append(codepoint);
          }
        }
        attribute.setValue(value.toString());

        outAttrsList.add(attribute);
      }
    }

    Collections.sort(outAttrsList, new Comparator<Attribute>() {
      public int compare(Attribute x, Attribute y) {
        String x_uri, y_uri;
        if (XML.equals(x.getPrefix())) {
          x_uri = node.lookupNamespaceURI(XML);
        } else {
          NamespaceContextParams x_stack = getLastElement(x.getPrefix());
          x_uri = x_stack != null ? x_stack.getUri() : "";
        }
        if (XML.equals(y.getPrefix())) {
          y_uri = node.lookupNamespaceURI(XML);
        } else {
          NamespaceContextParams y_stack = getLastElement(y.getPrefix());
          y_uri = y_stack != null ? y_stack.getUri() : "";
        }
        return String.format("%s:%s", x_uri, x.getLocalName()).compareTo(
            String.format("%s:%s", y_uri, y.getLocalName()));
      }
    });

    return outAttrsList;
  }

  /**
   * Prosessing of namespace attributes.
   * 
   * @param node
   *          DOM node
   * 
   * @return Returns a list of output namespace attributes
   */
  private List<NamespaceContextParams> processNamespaces(Node node) {
    addNamespaces(node);

    List<NamespaceContextParams> outNSList = new ArrayList<NamespaceContextParams>();

    String nPrefix = getNodePrefix(node);

    String childText = null;
    if (parameters.getQnameAwareElements().size() > 0 ||
        (parameters.getQnameAwareXPathElements().size() > 0 &&
      node.getChildNodes().getLength() == 1)) {

      childText = node.getTextContent();
    }

    int depth = getNodeDepth(node);
    for (String prefix : namespaces.keySet()) {
      NamespaceContextParams ncp = getLastElement(prefix);
      if (ncp.getDepth() != depth) {
        NamespaceContextParams entry = ncp.clone();
        if (entry.isHasOutput() != null && depth > 0)
          entry.setHasOutput(false);
        entry.setDepth(depth);
        namespaces.get(prefix).add(entry);
        ncp = entry;
      }
      if (ncp.isHasOutput() != null && !ncp.isHasOutput()) {
        if (isPrefixVisible(node, prefix, childText, nPrefix)) {
          NamespaceContextParams entry = ncp.clone();
          entry.setPrefix(prefix);
          outNSList.add(entry);
        } else
          continue;
        ncp.setHasOutput(true);
      }
    }

    if (bSequential) {
      Collections.sort(outNSList, new Comparator<NamespaceContextParams>() {
        public int compare(NamespaceContextParams x, NamespaceContextParams y) {
          return x.getUri().compareTo(y.getUri());
        }
      });

      for (NamespaceContextParams entry : outNSList) {
        NamespaceContextParams ncp = getLastElement(entry.getPrefix());
        if (!sequentialUriMap.containsKey(entry.getUri()))
          sequentialUriMap.put(entry.getUri(),
              String.format("n%s", sequentialUriMap.size()));
        entry.setNewPrefix(sequentialUriMap.get(entry.getUri()));
        ncp.setNewPrefix(entry.getNewPrefix());
      }
    } else {
      Collections.sort(outNSList, new Comparator<NamespaceContextParams>() {
        public int compare(NamespaceContextParams x, NamespaceContextParams y) {
          return x.getPrefix().compareTo(y.getPrefix());
        }
      });
    }
    return outNSList;
  }

  /**
   * Add namespaces to stack.
   * 
   * @param node
   *          DOM node
   */
  private void addNamespaces(Node node) {
    for (int ni = 0; ni < node.getAttributes().getLength(); ni++) {
      Node attr = node.getAttributes().item(ni);
      if (isInExcludeList(attr))
        continue;
      String prefix = getLocalName(attr);

      String prfxNs = getNodePrefix(attr);

      if (NS.equals(prfxNs) || (DEFAULT_NS.equals(prfxNs) && NS.equals(prefix))) {
        if (NS.equals(prefix)) {
          prefix = "";
        }

        String uri = attr.getNodeValue();

        List<NamespaceContextParams> stack = namespaces.get(prefix);
        if (stack != null && uri.equals(getLastElement(prefix).getUri()))
          continue;

        if (!namespaces.containsKey((prefix))) {
          namespaces.put(prefix, new ArrayList<NamespaceContextParams>());
        }
        NamespaceContextParams nsp = new NamespaceContextParams(uri, false,
            prefix, getNodeDepth(node));
        if (namespaces.get(prefix).size() == 0
            || getNodeDepth(node) != getLastElement(prefix).getDepth())
          namespaces.get(prefix).add(nsp);
        else
          namespaces.get(prefix).set(namespaces.get(prefix).size() - 1, nsp);
      }
    }
  }

  /**
   * Returns whether to show the prefix in the output of the node.
   * 
   * @param node
   *          DOM node
   * @param prefix
   *          prefix
   * 
   * @return Returns true if prefix is shown in the output of the node, false -
   *         otherwise.
   */
  private boolean isPrefixVisible(Node node, String prefix, String childText, String nPrefix) {

    if (nPrefix.equals(prefix)) {
      return true;
    }

    String nodeLocalName = getLocalName(node);
    if (parameters.getQnameAwareElements().size() > 0) {
      NamespaceContextParams ncp = getLastElement(prefix);
      String prfx = ncp.getPrefix();
      if (childText == null) {
        childText = node.getTextContent();
      }
      if (childText != null && childText.startsWith(prfx + C)
          && node.getChildNodes().getLength() == 1) {
        NamespaceContextParams attrPrfxNcp = getLastElement(nPrefix);
        for (QNameAwareParameter en : parameters.getQnameAwareElements()) {
          if (nodeLocalName.equals(en.getName())
              && en.getNs().equals(attrPrfxNcp.getUri())) {
            return true;
          }
        }
      }
    }
    if (parameters.getQnameAwareXPathElements().size() > 0
        && node.getChildNodes().getLength() == 1) {
      NamespaceContextParams ncp = getLastElement(nPrefix);
      if (childText == null) {
        childText = node.getTextContent();
      }
      for (QNameAwareParameter en : parameters.getQnameAwareXPathElements()) {
        if (nodeLocalName.equals(en.getName())
            && ncp.getUri().equals(en.getNs())) {
          NSContext nsContext = xpathesNsMap.get(childText);
          try {
            if (nsContext == null) {
              nsContext = new NSContext();
              XPathParser xpathParser = new XPathParser(null, null);
              org.apache.xpath.compiler.Compiler xpathCompiler = new org.apache.xpath.compiler.Compiler();
              xpathParser.initXPath(xpathCompiler, childText, nsContext);
              xpathesNsMap.put(childText, nsContext);
              nsContext.setWords(xpathCompiler.getTokenQueue());
            }
            if (nsContext.getXpathNs().contains(prefix))
              return true;
          } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
          }
        }
      }
    }

    NamespaceContextParams ncp = getLastElement(prefix);
    String prfx = ncp.getPrefix();
    for (int ai = 0; ai < node.getAttributes().getLength(); ai++) {
      Node attr = node.getAttributes().item(ai);
      String attrPrfx = getNodePrefix(attr);
      if (!attrPrfx.isEmpty() && attrPrfx.equals(prefix)) {
        return true;
      }
      if (parameters.getQnameAwareAttributes().size() > 0) {
        String attrValue = attr.getNodeValue();
        if (attrValue.startsWith(prfx + C)) {
          String attrLocalName = getLocalName(attr);
          String attrPrefix = getNodePrefix(attr);
          NamespaceContextParams attrPrfxNcp = getLastElement(attrPrefix);
          for (QNameAwareParameter en : parameters.getQnameAwareAttributes()) {
            if (attrLocalName.equals(en.getName())
                && en.getNs().equals(attrPrfxNcp.getUri())) {
              return true;
            }
          }
        }
      }
    }

    return false;
  }

  /**
   * Replace special characters.
   * 
   * @param text
   *          input text
   * @param bAttr
   *          true if text is attribute value
   * 
   * @return replacement text
   */
  private String processText(String text, boolean bAttr) {
    text = StringUtils.replace(text,"&", "&amp;");
    text = StringUtils.replace(text, "<", "&lt;");
    if (!bAttr) {
      text = StringUtils.replace(text, ">", "&gt;");
    } else {
      text = StringUtils.replace(text, "\"", "&quot;");
      text = StringUtils.replace(text, "#xA", "&#xA;");
      text = StringUtils.replace(text, "#x9", "&#x9;");
    }
    text = StringUtils.replace(text, "#xD", "&#xD;");
    return text;
  }

  /**
   * Returns the node local name.
   * 
   * @param node
   *          DOM node
   * @return Returns local name
   */
  private String getLocalName(Node node) {
    if (node.getLocalName() != null)
      return node.getLocalName();
    String name = node.getNodeName();
    int idx = name.indexOf(C);
    if (idx > -1)
      return name.substring(idx + 1);
    return name;
  }

  /**
   * Returns parameter by key.
   * 
   * @param key
   *          key
   * @return parameter
   */
  private NamespaceContextParams getLastElement(String key) {
    return getLastElement(key, -1);
  }

  /**
   * Returns parameter by key.
   * 
   * @param key
   *          key
   * @param shift
   *          shift
   * @return parameter
   */
  private NamespaceContextParams getLastElement(String key, int shift) {
    List<NamespaceContextParams> lst = namespaces.get(key);
    return lst.size() + shift > -1 ? lst.get(lst.size() + shift) : null;
  }

  /**
   * Returns the node prefix.
   * 
   * @param node
   *          DOM node
   * @return Returns prefix
   */
  private String getNodePrefix(Node node) {
    String prfx = node.getPrefix();
    if (prfx == null || prfx.isEmpty()) {
      prfx = "";
      String name = node.getNodeName();
      int idx = name.indexOf(C);
      if (idx > -1)
        return StringUtils.substring(name,0, idx);
    }
    return prfx;
  }
}
