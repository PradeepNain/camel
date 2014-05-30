/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.support;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.camel.Exchange;
import org.apache.camel.InvalidPayloadException;
import org.apache.camel.converter.jaxp.StaxConverter;
import org.apache.camel.spi.NamespaceAware;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class XMLTokenExpressionIterator extends ExpressionAdapter implements NamespaceAware {
    protected final String path;
    protected boolean wrap;
    protected Map<String, String> nsmap;

    public XMLTokenExpressionIterator(String path, boolean wrap) {
        ObjectHelper.notEmpty(path, "path");
        this.path = path;
        this.wrap = wrap;
    }

    @Override
    public void setNamespaces(Map<String, String> nsmap) {
        this.nsmap = nsmap;
    }

    public void setWrap(boolean wrap) {
        this.wrap = wrap;
    }
    
    protected Iterator<?> createIterator(InputStream in, Exchange exchange) throws XMLStreamException {
        XMLTokenIterator iterator = new XMLTokenIterator(path, nsmap, wrap, in, exchange);
        return iterator;
    }

    @Override
    public boolean matches(Exchange exchange) {
        // as a predicate we must close the stream, as we do not return an iterator that can be used
        // afterwards to iterate the input stream
        Object value = doEvaluate(exchange, true);
        return ObjectHelper.evaluateValuePredicate(value);
    }

    @Override
    public Object evaluate(Exchange exchange) {
        // as we return an iterator to access the input stream, we should not close it
        return doEvaluate(exchange, false);
    }

    /**
     * Strategy to evaluate the exchange
     *
     * @param exchange   the exchange
     * @param closeStream whether to close the stream before returning from this method.
     * @return the evaluated value
     */
    protected Object doEvaluate(Exchange exchange, boolean closeStream) {
        InputStream in = null;
        try {
            in = exchange.getIn().getMandatoryBody(InputStream.class);
            return createIterator(in, exchange);
        } catch (InvalidPayloadException e) {
            exchange.setException(e);
            // must close input stream
            IOHelper.close(in);
            return null;
        } catch (XMLStreamException e) {
            exchange.setException(e);
            // must close input stream
            IOHelper.close(in);
            return null;
        } finally {
            if (closeStream) {
                IOHelper.close(in);
            }
        }
    }
    

    static class XMLTokenIterator implements Iterator<Object>, Closeable {
        private static final Logger LOG = LoggerFactory.getLogger(XMLTokenIterator.class);
        private static final Pattern NAMESPACE_PATTERN = Pattern.compile("xmlns(:\\w+|)\\s*=\\s*('[^']+'|\"[^\"]+\")");

        private AttributedQName[] splitpath;
        private int index;
        private boolean wrap;
        private RecordableInputStream in;
        private XMLStreamReader reader;
        private List<QName> path;
        private List<Map<String, String>> namespaces;
        private List<String> segments;
        private List<QName> segmentlog;
        private int code;
        private int consumed;
        private boolean backtrack;
        private int trackdepth = -1;
        private int depth;
        
        private Object nextToken;
        
        public XMLTokenIterator(String path, Map<String, String> nsmap, boolean wrap, InputStream in, Exchange exchange) throws XMLStreamException {
            final String[] sl = path.substring(1).split("/");
            this.splitpath = new AttributedQName[sl.length];
            for (int i = 0; i < sl.length; i++) {
                String s = sl[i];
                if (s.length() > 0) {
                    int d = s.indexOf(':');
                    String pfx = d > 0 ? s.substring(0, d) : "";
                    this.splitpath[i] = 
                        new AttributedQName(
                            "*".equals(pfx) ? "*" : nsmap.get(pfx), d > 0 ? s.substring(d + 1) : s, pfx);
                }
            }
            
            this.wrap = wrap;
            String charset = IOHelper.getCharsetName(exchange, false);
            this.in = new RecordableInputStream(in, charset);
            this.reader = new StaxConverter().createXMLStreamReader(this.in, exchange);

            LOG.trace("reader.class: {}", reader.getClass());
            int coff = reader.getLocation().getCharacterOffset();
            if (coff != 0) {
                LOG.error("XMLStreamReader {} not supporting Location");
                throw new XMLStreamException("reader not supporting Location");
            }

            this.path = new ArrayList<QName>();
            
            // wrapped mode needs the segments and the injected mode needs the namespaces
            if (wrap) {
                this.segments = new ArrayList<String>();
                this.segmentlog = new ArrayList<QName>();
            } else {
                this.namespaces = new ArrayList<Map<String, String>>();
            }
                        
            this.nextToken = getNextToken();

        }
        
        private boolean isDoS() {
            return splitpath[index] == null;
        }
        
        private AttributedQName current() {
            return splitpath[index + (isDoS() ? 1 : 0)];
        }
        
        private AttributedQName ancestor() {
            return index == 0 ? null : splitpath[index - 1];
        }

        private void down() {
            if (isDoS()) {
                index++;
            }
            index++;
        }
        
        private void up() {
            index--;
        }
        
        private boolean isBottom() {
            return index == splitpath.length - (isDoS() ? 2 : 1);
        }
        
        private boolean isTop() {
            return index == 0;
        }
        
        private int readNext() throws XMLStreamException {
            int c = code;
            if (c > 0) {
                code = 0;
            } else {
                c = reader.next();
            }
            return c;
        }
        
        private String getCurrenText() {
            int pos = reader.getLocation().getCharacterOffset();
            String txt = in.getText(pos - consumed);
            consumed = pos;
            // keep recording
            in.record();
            return txt;
        }

        private void pushName(QName name) {
            path.add(name);
        }

        private QName popName() {
            return path.remove(path.size() - 1);
        }

        private void pushSegment(QName qname, String token) {
            segments.add(token);
            segmentlog.add(qname);
        }

        private String popSegment() {
            return segments.remove(segments.size() - 1);
        }
        
        private QName peekLog() {
            return segmentlog.get(segmentlog.size() - 1);
        }
        
        private QName popLog() {
            return segmentlog.remove(segmentlog.size() - 1);
        }

        private void pushNamespaces(XMLStreamReader reader) {
            Map<String, String> m = new HashMap<String, String>();
            if (namespaces.size() > 0) {
                m.putAll(namespaces.get(namespaces.size() - 1));
            }
            for (int i = 0; i < reader.getNamespaceCount(); i++) {
                m.put(reader.getNamespacePrefix(i), reader.getNamespaceURI(i));
            }
            namespaces.add(m);
        }

        private void popNamespaces() {
            namespaces.remove(namespaces.size() - 1);
        }

        private Map<String, String> getCurrentNamespaceBindings() {
            return namespaces.get(namespaces.size() - 1);
        }

        private void readCurrent(boolean incl) throws XMLStreamException {
            int d = depth;
            while (d <= depth) {
                int code = reader.next();
                if (code == XMLStreamReader.START_ELEMENT) {
                    depth++;
                } else if (code == XMLStreamReader.END_ELEMENT) {
                    depth--;
                }
            }
            // either look ahead to the next token or stay at the end element token
            if (incl) {
                code = reader.next();
            } else {
                code = reader.getEventType();
                if (code == XMLStreamReader.END_ELEMENT) {
                    // revert the depth count to avoid double counting the up event
                    depth++;
                }
            }
        }

        private String getCurrentToken() throws XMLStreamException {
            readCurrent(true);
            popName();
            
            String token = createContextualToken(getCurrenText());
            if (!wrap) {
                popNamespaces();
            }
            
            return token;
        }

        private String createContextualToken(String token) {
            StringBuilder sb = new StringBuilder();
            if (wrap) {
                for (int i = 0; i < segments.size(); i++) {
                    sb.append(segments.get(i));
                }
                sb.append(token);
                for (int i = path.size() - 1; i >= 0; i--) {
                    QName q = path.get(i);
                    sb.append("</").append(makeName(q)).append(">");
                }

            } else {
                final String stag = token.substring(0, token.indexOf('>') + 1);
                Set<String> skip = new HashSet<String>();
                Matcher matcher = NAMESPACE_PATTERN.matcher(stag);
                char quote = 0;
                while (matcher.find()) {
                    String prefix = matcher.group(1);
                    if (prefix.length() > 0) {
                        prefix = prefix.substring(1);
                    }
                    skip.add(prefix);
                    if (quote == 0) {
                        quote = matcher.group(2).charAt(0);
                    }
                }
                if (quote == 0) {
                    quote = '"';
                }
                boolean empty = stag.endsWith("/>"); 
                sb.append(token.substring(0, stag.length() - (empty ? 2 : 1)));
                for (Entry<String, String> e : getCurrentNamespaceBindings().entrySet()) {
                    if (!skip.contains(e.getKey())) {
                        sb.append(e.getKey().length() == 0 ? " xmlns" : " xmlns:")
                            .append(e.getKey()).append("=").append(quote).append(e.getValue()).append(quote);
                    }
                }
                sb.append(token.substring(stag.length() - (empty ? 2 : 1)));
            }

            return sb.toString();
        }

        private String getNextToken() throws XMLStreamException {
            int code = 0;
            while (code != XMLStreamReader.END_DOCUMENT) {
                code = readNext();

                switch (code) {
                case XMLStreamReader.START_ELEMENT:
                    depth++;
                    QName name = reader.getName();
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("se={}; depth={}; trackdepth={}", new Object[]{name, depth, trackdepth});
                    }
                    
                    String token = getCurrenText();
                    LOG.trace("token={}", token);
                    if (!backtrack && wrap) {
                        pushSegment(name, token);
                    }
                    pushName(name);
                    if (!wrap) {
                        pushNamespaces(reader);
                    }
                    backtrack = false;
                    if (current().matches(name)) {
                        // mark the position of the match in the segments list
                        if (isBottom()) {
                            // final match
                            token = getCurrentToken();
                            backtrack = true;
                            trackdepth = depth;
                            return token;
                        } else {
                            // intermediary match
                            down();
                        }
                    } else if (isDoS()) {
                        // continue
                    } else {
                        // skip
                        readCurrent(false);
                    }
                    break;
                case XMLStreamReader.END_ELEMENT:
                    depth--;
                    QName endname = reader.getName();
                    LOG.trace("ee={}", endname);
                    
                    popName();
                    if (!wrap) {
                        popNamespaces();
                    }
                    
                    int pc = 0;
                    if (backtrack || (trackdepth > 0 && depth == trackdepth - 1)) {
                        // reactive backtrack if not backtracking and update the track depth
                        backtrack = true;
                        trackdepth--;
                        if (wrap) {
                            while (!endname.equals(peekLog())) {
                                pc++;
                                popLog();
                            }
                        }
                    }

                    if (backtrack) {
                        if (wrap) {
                            for (int i = 0; i < pc; i++) {
                                popSegment();
                            }
                        }

                        if ((ancestor() == null && !isTop())
                            || (ancestor() != null && ancestor().matches(endname))) {
                            up();
                        }
                    }
                    break;
                case XMLStreamReader.END_DOCUMENT:
                    LOG.trace("depth={}", depth);
                    break;
                default:
                    break;
                }
            }
            return null;
        }

        private static String makeName(QName qname) {
            String pfx = qname.getPrefix();
            return pfx.length() == 0 ? qname.getLocalPart() : qname.getPrefix() + ":" + qname.getLocalPart();
        }

        @Override
        public boolean hasNext() {
            return nextToken != null;
        }

        @Override
        public Object next() {
            Object o = nextToken;
            try {
                nextToken = getNextToken();
            } catch (XMLStreamException e) {
                //
            }
            return o;
        }

        @Override
        public void remove() {
            // noop
        }

        @Override
        public void close() throws IOException {
            try {
                reader.close();
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
        }
    }

    static class AttributedQName extends QName {
        private static final long serialVersionUID = 9878370226894144L;
        private Pattern lcpattern;
        private boolean nsany;
        
        public AttributedQName(String localPart) {
            super(localPart);
            checkWildcard("", localPart);
        }

        public AttributedQName(String namespaceURI, String localPart, String prefix) {
            super(namespaceURI, localPart, prefix);
            checkWildcard(namespaceURI, localPart);
        }

        public AttributedQName(String namespaceURI, String localPart) {
            super(namespaceURI, localPart);
            checkWildcard(namespaceURI, localPart);
        }

        public boolean matches(QName qname) {
            return (nsany || getNamespaceURI().equals(qname.getNamespaceURI()))
                && (lcpattern != null 
                ? lcpattern.matcher(qname.getLocalPart()).matches() 
                : getLocalPart().equals(qname.getLocalPart()));
        }
        
        private void checkWildcard(String nsa, String lcp) {
            nsany = "*".equals(nsa);
            boolean wc = false;
            for (int i = 0; i < lcp.length(); i++) {
                char c = lcp.charAt(i);
                if (c == '?' || c == '*') {
                    wc = true;
                    break;
                }
            }
            if (wc) {
                lcpattern = Pattern.compile(lcp.replace(".", "\\.").replace("*", ".*").replace("?", "."));
            }
        }
    }
}
