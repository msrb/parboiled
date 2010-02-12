/*
 * Copyright (C) 2009 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.parboiled;

import org.jetbrains.annotations.NotNull;
import org.parboiled.common.Reference;
import org.parboiled.common.StringUtils;
import org.parboiled.errorhandling.ParseError;
import org.parboiled.errorhandling.ParseErrorHandler;
import org.parboiled.errorhandling.SimpleParseError;
import org.parboiled.exceptions.ParserRuntimeException;
import org.parboiled.matchers.ActionMatcher;
import org.parboiled.matchers.Matcher;
import org.parboiled.matchers.ProxyMatcher;
import org.parboiled.matchers.TestMatcher;
import org.parboiled.support.*;
import static org.parboiled.support.ParseTreeUtils.*;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>The Context implementation orchestrating most of the matching process.</p>
 * <p>The parsing process works as following:</br>
 * After the rule tree (which is in fact a directed and potentially even cyclic graph of Matcher instances) has been
 * created a root MatcherContext is instantiated for the root rule (Matcher).
 * A subsequent call to {@link #runMatcher()} starts the parsing process.</p>
 * <p>The MatcherContext essentially calls {@link Matcher#match(MatcherContext)} passing itself to the Matcher
 * which executes its logic, potentially calling sub matchers. For each sub matcher the matcher calls
 * {@link #runMatcher()} on its Context, which creates a sub context of the
 * current MatcherContext and runs the given sub matcher in it.</p>
 * <p>This basically creates a stack of MatcherContexts, each corresponding to their rule matchers. The MatcherContext
 * instances serve as a kind of companion objects to the matchers, providing them with support for building the
 * parse tree nodes, keeping track of input locations and error recovery.</p>
 * <p>At each point during the parsing process the matchers and action expressions have access to the current
 * MatcherContext and all "open" parent MatcherContexts through the {@link #getParent()} chain.</p>
 *
 * @param <V> the node value type
 */
public class MatcherContext<V> implements Context<V> {

    private final InputBuffer inputBuffer;
    private final BaseParser<V> parser;
    private final List<ParseError> parseErrors;
    private final ParseErrorHandler<V> parseErrorHandler;
    private final Reference<Node<V>> lastNodeRef;
    private final MatcherContext<V> parent;
    private final int level;

    private MatcherContext<V> subContext;
    private InputLocation startLocation;
    private InputLocation currentLocation;
    private Matcher<V> matcher;
    private Node<V> node;
    private List<Node<V>> subNodes;
    private V nodeValue;
    private int intTag;
    private boolean belowLeafLevel;

    public MatcherContext(@NotNull InputBuffer inputBuffer, @NotNull BaseParser<V> parser,
                          @NotNull List<ParseError> parseErrors,
                          @NotNull ParseErrorHandler<V> parseErrorHandler, @NotNull Matcher<V> matcher) {
        this(inputBuffer, parser, parseErrors, parseErrorHandler, new Reference<Node<V>>(), null, 0);
        setStartLocation(new InputLocation(inputBuffer));
        this.matcher = ProxyMatcher.unwrap(matcher);
    }

    private MatcherContext(@NotNull InputBuffer inputBuffer, @NotNull BaseParser<V> parser,
                           @NotNull List<ParseError> parseErrors, @NotNull ParseErrorHandler<V> parseErrorHandler,
                           @NotNull Reference<Node<V>> lastNodeRef, MatcherContext<V> parent, int level) {
        this.inputBuffer = inputBuffer;
        this.parser = parser;
        this.parseErrors = parseErrors;
        this.parseErrorHandler = parseErrorHandler;
        this.lastNodeRef = lastNodeRef;
        this.parent = parent;
        this.level = level;
    }

    @Override
    public String toString() {
        return getPath().toString();
    }

    //////////////////////////////// CONTEXT INTERFACE ////////////////////////////////////

    public MatcherContext<V> getParent() {
        return parent;
    }

    public MatcherContext<V> getSubContext() {
        // if the subContext has a null matcher it has been retired and is invalid
        return subContext != null && subContext.matcher != null ? subContext : null;
    }

    @NotNull
    public InputBuffer getInputBuffer() {
        return inputBuffer;
    }

    public InputLocation getStartLocation() {
        return startLocation;
    }

    public Matcher<V> getMatcher() {
        return matcher;
    }

    @NotNull
    public List<ParseError> getParseErrors() {
        return parseErrors;
    }

    public InputLocation getCurrentLocation() {
        return currentLocation;
    }

    public String getNodeText(Node<?> node) {
        return ParseTreeUtils.getNodeText(node, inputBuffer);
    }

    public Character getNodeChar(Node<?> node) {
        return ParseTreeUtils.getNodeChar(node, inputBuffer);
    }

    @NotNull
    public MatcherPath<V> getPath() {
        return new MatcherPath<V>(this);
    }

    public int getLevel() {
        return level;
    }

    public V getNodeValue() {
        return nodeValue;
    }

    public void setNodeValue(V value) {
        this.nodeValue = value;
    }

    public V getTreeValue() {
        V treeValue = nodeValue;
        if (subNodes != null) {
            int i = subNodes.size();
            while (treeValue == null && i-- > 0) {
                treeValue = subNodes.get(i).getValue();
            }
        }
        return treeValue;
    }

    public Node<V> getNodeByPath(String path) {
        return findNodeByPath(subNodes, path);
    }

    public Node<V> getNodeByLabel(String labelPrefix) {
        return subNodes != null ? findNode(subNodes, new LabelPrefixPredicate<V>(labelPrefix)) : null;
    }

    public Node<V> getLastNode() {
        return lastNodeRef.getTarget();
    }

    public List<Node<V>> getSubNodes() {
        return subNodes;
    }

    public boolean inPredicate() {
        return matcher instanceof TestMatcher || parent != null && parent.inPredicate();
    }

    public boolean isBelowLeafLevel() {
        return belowLeafLevel;
    }

    @NotNull
    public BaseParser<V> getParser() {
        return parser;
    }

    public void injectVirtualInput(char virtualInputChar) {
        currentLocation = currentLocation.insertVirtualInput(virtualInputChar);
    }

    public void injectVirtualInput(String virtualInputText) {
        currentLocation = currentLocation.insertVirtualInput(virtualInputText);
    }

    //////////////////////////////// PUBLIC ////////////////////////////////////

    public void setCurrentLocation(InputLocation currentLocation) {
        this.currentLocation = currentLocation;
    }

    public void setStartLocation(InputLocation location) {
        startLocation = currentLocation = location;
    }

    public void advanceInputLocation() {
        setCurrentLocation(currentLocation.advance(inputBuffer));
    }

    public Node<V> getNode() {
        return node;
    }

    public int getIntTag() {
        return intTag;
    }

    public void setIntTag(int intTag) {
        this.intTag = intTag;
    }

    public void createNode() {
        if (belowLeafLevel || matcher instanceof TestMatcher) {
            return;
        }
        if (matcher.isWithoutNode()) {
            if (parent != null && subNodes != null) parent.addChildNodes(subNodes);
            return;
        }
        node = new NodeImpl<V>(matcher.getLabel(), subNodes, startLocation, currentLocation, getTreeValue());
        if (parent != null) parent.addChildNode(node);
        lastNodeRef.setTarget(node);
    }

    public void addChildNode(@NotNull Node<V> node) {
        if (subNodes == null) subNodes = new ArrayList<Node<V>>();
        subNodes.add(node);
    }

    public void addChildNodes(@NotNull List<Node<V>> nodes) {
        if (subNodes == null) subNodes = new ArrayList<Node<V>>();
        subNodes.addAll(nodes);
    }

    public MatcherContext<V> getSubContext(Matcher<V> matcher) {
        if (subContext == null) {
            // we need to introduce a new level
            subContext = new MatcherContext<V>(inputBuffer, parser, parseErrors, parseErrorHandler, lastNodeRef, this,
                    level + 1);
        }

        // normally we just reuse the existing subContext instance
        subContext.matcher = ProxyMatcher.unwrap(matcher);
        subContext.setStartLocation(currentLocation);
        subContext.node = null;
        subContext.subNodes = null;
        subContext.nodeValue = null;
        subContext.belowLeafLevel = belowLeafLevel || this.matcher.isLeaf();
        return subContext;
    }

    public boolean runMatcher() {
        try {
            if (matcher.match(this)) {
                parseErrorHandler.handleMatch(this);
            } else {
                if (!parseErrorHandler.handleMismatch(this)) {
                    matcher = null; // "retire" this context
                    return false;
                }
            }
            if (parent != null) parent.setCurrentLocation(currentLocation);
            matcher = null; // "retire" this context until is "activated" again by a getSubContext(...) on the parent
            return true;

        } catch (ParserRuntimeException e) {
            throw e; // don't wrap, just bubble up
        } catch (Throwable e) {
            throw new ParserRuntimeException(e,
                    printParseError(new SimpleParseError(currentLocation,
                            StringUtils.escape(String.format("Error while parsing %s '%s' at input position",
                                    matcher instanceof ActionMatcher ? "action" : "rule", getPath()))), inputBuffer));
        }
    }

}
