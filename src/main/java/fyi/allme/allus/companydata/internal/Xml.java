package fyi.allme.allus.companydata.internal;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inverse of the platform's XML serialization:
 *
 * <ul>
 *   <li>the document root is {@code <response>};</li>
 *   <li>a PHP list (int keys) renders as repeated {@code <item>} children — so an
 *       element whose every child is {@code <item>} becomes a Java {@code List};</li>
 *   <li>an associative array renders as named child tags — a Java {@code Map};</li>
 *   <li>scalars are element text; booleans came over as {@code "true"}/{@code "false"}.</li>
 * </ul>
 *
 * <p><b>XXE-safe.</b> The {@link DocumentBuilderFactory} is
 * hardened: DOCTYPE declarations are DISALLOWED, external general/parameter
 * entities are disabled, entity-reference expansion is off, and secure-processing
 * is on. No external DTD/entity is ever resolved. (HMAC is always computed over
 * the raw bytes by the caller, never the parsed tree.)
 */
public final class Xml {
    private Xml() {
    }

    private static DocumentBuilderFactory hardenedFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            // The single most important guard: forbid DOCTYPE entirely (kills XXE
            // + entity-expansion DoS at the source).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException exc) {
            // If a feature isn't recognized, the others (esp. disallow-doctype-decl)
            // still apply; fall through with what we could set.
            throw new IllegalStateException("could not harden XML parser: " + exc.getMessage(), exc);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        return factory;
    }

    /** Parse the platform's XML serialization into Java data (Map/List/String). */
    public static Object parse(String text) throws Exception {
        DocumentBuilder builder = hardenedFactory().newDocumentBuilder();
        // No external resolution: any DOCTYPE would already have failed above.
        Document doc = builder.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        return elementToJava(doc.getDocumentElement());
    }

    private static Object elementToJava(Element elem) {
        List<Element> children = childElements(elem);
        if (children.isEmpty()) {
            return textOf(elem);
        }
        // All children are <item> → a list (PHP int-keyed array).
        boolean allItems = true;
        for (Element child : children) {
            if (!"item".equals(child.getTagName())) {
                allItems = false;
                break;
            }
        }
        if (allItems) {
            List<Object> list = new ArrayList<>(children.size());
            for (Element child : children) {
                list.add(elementToJava(child));
            }
            return list;
        }
        // Otherwise an object: named tags → map keys. Repeated tags collapse to a list.
        Map<String, Object> result = new LinkedHashMap<>();
        for (Element child : children) {
            String tag = child.getTagName();
            Object value = elementToJava(child);
            if (result.containsKey(tag)) {
                Object existing = result.get(tag);
                if (existing instanceof List<?>) {
                    @SuppressWarnings("unchecked")
                    List<Object> l = (List<Object>) existing;
                    l.add(value);
                } else {
                    List<Object> l = new ArrayList<>();
                    l.add(existing);
                    l.add(value);
                    result.put(tag, l);
                }
            } else {
                result.put(tag, value);
            }
        }
        return result;
    }

    private static List<Element> childElements(Element elem) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = elem.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String textOf(Element elem) {
        StringBuilder sb = new StringBuilder();
        NodeList nodes = elem.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Text t) {
                sb.append(t.getData());
            }
        }
        return sb.toString();
    }
}
