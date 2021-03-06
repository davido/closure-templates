/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.parsepasses.contextautoesc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.EscapingMode;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.PrintDirectiveNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateBasicNode;
import com.google.template.soy.soytree.TemplateBasicNodeBuilder;
import com.google.template.soy.soytree.TemplateDelegateNode;
import com.google.template.soy.soytree.TemplateDelegateNodeBuilder;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.TemplateNode.SoyFileHeaderInfo;
import com.google.template.soy.soytree.defn.TemplateParam;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Encapsulates information inferred about a Soy file and decisions made to change it.
 *
 * <p>The mutator methods on this class do not change the Soy file. All changes are delayed until
 * after all inference has been done so that we can safely try a variety of speculative techniques.
 *
 * <p>To make it easier to do speculative inference, this class cascades : each instance has a
 * parent, and it delegates to that when it does not have info itself. And there is a {@link
 * Inferences#foldIntoParent()} method that propagates all decisions into the parent when a set of
 * inference decisions are considered final.
 *
 * <p>The {@link ContextualAutoescaper} creates a single root instance and its passes fold
 * successful inferences into the parent until it ends up with a final set of rewriting decisions
 * that the {@link Rewriter} applies to the input Soy parse tree.
 *
 */
final class Inferences {

  /** Null or an instance to inherit state from. */
  private final @Nullable Inferences parent;

  /** Used to generate unique IDs for cloned templates. */
  private final IdGenerator idGen;

  /** Map of template names to instances used to type <code>{call}</code> commands. */
  private final ListMultimap<String, TemplateNode> templatesByName =
      MultimapBuilder.linkedHashKeys().arrayListValues().build();

  /** The types of templates. */
  private final Map<String, Context> templateNameToEndContext = Maps.newLinkedHashMap();

  /** Maps print, msg and call commands to the inferred escaping modes. */
  private final Map<SoyNode, ImmutableList<EscapingMode>> nodeToEscapingModes =
      Maps.newIdentityHashMap();

  /** Maps print, msg and call commands to the context. */
  private final Map<SoyNode, Context> nodeToContext = Maps.newIdentityHashMap();

  /** Maps IDs of <code>{call}</code> commands to the derived template they should use. */
  private final Map<CallNode, String> callNodeToDerivedCalleeName = Maps.newIdentityHashMap();

  /** The set of template names checked.  Used to identify re-entrant templates. */
  private final Set<String> templatesChecked = Sets.newHashSet();

  /**
   * An instance that inherits from a parent.
   */
  public Inferences(Inferences parent) {
    this.parent = parent;
    this.idGen = parent.idGen;
  }

  /**
   * An instance that does not inherit from a parent.
   *
   * @param idGen Used to generate unique IDs for cloned templates.
   * @param templatesByName Map of template names to instances used to type <code>{call}</code>
   *     commands.
   */
  public Inferences(
      IdGenerator idGen, ImmutableListMultimap<String, TemplateNode> templatesByName) {
    this.parent = null;
    this.idGen = idGen;
    this.templatesByName.putAll(templatesByName);
  }

  /**
   * Stores a type conclusion.  This may be speculative.
   * @param templateName A qualified template name.
   */
  public void recordTemplateEndContext(String templateName, Context context) {
    templateNameToEndContext.put(templateName, context);
  }

  /**
   * Finds the named templates.
   *
   * @param templateName A qualified template name.
   */
  List<TemplateNode> lookupTemplates(String templateName) {
    for (Inferences inferences = this; inferences != null; inferences = inferences.parent) {
      List<TemplateNode> tn = inferences.templatesByName.get(templateName);
      if (!tn.isEmpty()) {
        return tn;
      }
    }
    return ImmutableList.of();
  }

  /**
   * Null if no typing has been done for the named template, or otherwise the context after a call
   * to the named template.  Since we derive templates by start context at the call site, there
   * is no start context parameter.
   *
   * @param templateName A qualified template name.
   */
  public Context getTemplateEndContext(String templateName) {
    for (Inferences inferences = this; inferences != null; inferences = inferences.parent) {
      Context oc = inferences.templateNameToEndContext.get(templateName);
      if (oc != null) {
        return oc;
      }
    }
    return null;
  }

  /**
   * Null if there is no escaping mode for the given <code>{print}</code> node.
   */
  public ImmutableList<EscapingMode> getEscapingMode(PrintNode printNode) {
    // See if we have already inferred an escaping mode for the node.
    for (Inferences inferences = this; inferences != null; inferences = inferences.parent) {
      ImmutableList<EscapingMode> escapingModes = inferences.nodeToEscapingModes.get(printNode);
      if (escapingModes != null) {
        return escapingModes;
      }
    }

    // Look for an escaping mode in the existing directives.
    ImmutableList.Builder<EscapingMode> modes = ImmutableList.builder();
    for (PrintDirectiveNode directiveNode : printNode.getChildren()) {
      EscapingMode mode = EscapingMode.fromDirective(directiveNode.getName());
      if (mode != null) {
        modes.add(mode);
      } else if (directiveNode.getPrintDirective() != null
          && directiveNode.getPrintDirective().shouldCancelAutoescape()) {
        modes.add(EscapingMode.NO_AUTOESCAPE);
      }
    }
    return modes.build();
  }

  /** Records inferred escaping modes so a directive can be later added to the Soy parse tree. */
  public void setEscapingDirectives(
      SoyNode node, Context context, List<EscapingMode> escapingModes) {
    Preconditions.checkArgument(
        (node instanceof PrintNode)
            || (node instanceof CallNode)
            || (node instanceof MsgFallbackGroupNode),
        "Escaping directives may only be set for {print}, {msg}, or {call} nodes");
    if (escapingModes != null) {
      nodeToEscapingModes.put(node, ImmutableList.copyOf(escapingModes));
    }
    nodeToContext.put(node, context);
  }

  /**
   * The escaping modes for the print command with the given ID in the order in which they should be
   * applied.
    *
   * @param node a node instance
   */
  public ImmutableList<EscapingMode> getEscapingModesForNode(SoyNode node) {
    ImmutableList<EscapingMode> modes = nodeToEscapingModes.get(node);
    if (modes == null) {
      modes = ImmutableList.of();
    }
    return modes;
  }

  @VisibleForTesting
  Context getContextForNode(SoyNode node) {
    return nodeToContext.get(node);
  }
  /**
   * Derives a <code>{call}</code> site so that it uses a version of the template appropriate to the
   * start context.
   *
   * @param derivedCalleeName A qualified template name.
   */
  public void retargetCall(CallNode cn, String derivedCalleeName) {
    callNodeToDerivedCalleeName.put(cn, derivedCalleeName);
  }

  /**
   * The name of the derived template that the call with the given id should call, or null if the
   * call with the given id should not be retargeted to a derived template.
   */
  @Nullable
  public String getDerivedCalleeNameForCall(CallNode callNode) {
    return callNodeToDerivedCalleeName.get(callNode);
  }

  /**
   * Clones a template, changing the name.
   *
   * @return A copy of tn, differing semantically only in name and auto-generated IDs. The new
   *     templates will be available via {@link #lookupTemplates} with the given name.
   */
  public List<TemplateNode> cloneTemplates(String baseName, String derivedName, CallNode callNode) {
    if (!lookupTemplates(derivedName).isEmpty()) {
      throw new AssertionError(
          derivedName + " already has templates: " + lookupTemplates(derivedName));
    }

    ImmutableList.Builder<TemplateNode> b = ImmutableList.builder();

    for (TemplateNode tn : lookupTemplates(baseName)) {
      if (SoyTreeUtils.hasHtmlNodes(tn)) {
        throw SoyAutoescapeException.createWithNode(
            "Non-strict template '"
                + baseName
                + "' contains HTML nodes but does not specify the kind. "
                + "This is no longer allowed, please migrate the template to strict and "
                + "specify a content kind by adding a "
                + "kind=\"(html|attributes|js|css|uri)\" attribute",
            callNode);
      }
      SoyFileHeaderInfo soyFileHeaderInfo = tn.getSoyFileHeaderInfo();

      // We trivially clone the template with new ids, this ensures that all the varrefs have proper
      // vardefns assigned.  Then we manually recopy to a new template in order to modify the name.
      // TODO(lukes): we should add direct support for swapping template names to TemplateNode
      // or just eliminate usecases for this method.
      TemplateNode trivialClonedTemplate = SoyTreeUtils.cloneWithNewIds(tn, idGen);
      int cloneId = trivialClonedTemplate.getId();

      // We need to use the unnamespaced name in the command text since we'll be inserting this
      // template into a file node that already has a namespace declaration.
      TemplateNode clone;

      if (tn instanceof TemplateBasicNode) {
        String derivedPartialName =
            (tn.getPartialTemplateName() != null)
                ? derivedName.substring(soyFileHeaderInfo.namespace.length())
                : null;
        clone =
            new TemplateBasicNodeBuilder(soyFileHeaderInfo, ErrorReporter.exploding())
                .setId(cloneId)
                .setSourceLocation(tn.getSourceLocation())
                .setCmdTextInfo(
                    derivedName,
                    derivedPartialName,
                    tn.getVisibility(),
                    tn.getAutoescapeMode(),
                    tn.getContentKind(),
                    tn.getRequiredCssNamespaces())
                .addParams(trivialClonedTemplate.getAllParams())
                .build();

        if (!(derivedName.equals(clone.getTemplateName())
            && Objects.equals(derivedPartialName, clone.getPartialTemplateName()))) {
          throw new AssertionError();
        }

      } else if (tn instanceof TemplateDelegateNode) {
        TemplateDelegateNode tdn = (TemplateDelegateNode) tn;
        clone =
            new TemplateDelegateNodeBuilder(soyFileHeaderInfo, ErrorReporter.exploding())
                .setId(cloneId)
                .setSourceLocation(tn.getSourceLocation())
                .setCmdTextInfo(
                    derivedName,
                    tdn.getDelTemplateVariant(),
                    tdn.getDelPriority(),
                    tn.getAutoescapeMode(),
                    tn.getContentKind(),
                    tn.getRequiredCssNamespaces())
                .addParams(trivialClonedTemplate.getAllParams())
                .build();
        if (!(derivedName.equals(((TemplateDelegateNode) clone).getDelTemplateName()))) {
          throw new AssertionError();
        }

      } else {
        throw new AssertionError("Unknown template node type: " + tn.getClass());
      }
      clone.addChildren(trivialClonedTemplate.getChildren());

      // Reassign all the local variable data which isn't maintained by the cloning process above.
      clone.setMaxLocalVariableTableSize(tn.getMaxLocalVariableTableSize());
      Iterator<TemplateParam> tnIterator = tn.getAllParams().iterator();
      Iterator<TemplateParam> cloneIterator = clone.getAllParams().iterator();
      while (tnIterator.hasNext()) {
        cloneIterator.next().setLocalVariableIndex(tnIterator.next().localVariableIndex());
      }

      b.add(clone);
    }

    ImmutableList<TemplateNode> clones = b.build();
    templatesByName.putAll(derivedName, clones);
    return clones;
  }

  /**
   * Folds speculative decisions into the parent passed to the constructor.
   * This instance should not be used after folding.
   */
  public void foldIntoParent() {
    parent.nodeToEscapingModes.putAll(nodeToEscapingModes);
    parent.templateNameToEndContext.putAll(templateNameToEndContext);
    parent.callNodeToDerivedCalleeName.putAll(callNodeToDerivedCalleeName);
    parent.templatesByName.putAll(templatesByName);
    parent.templatesChecked.addAll(templatesChecked);
  }

  /**
   * All known templates.
   */
  public List<TemplateNode> getAllTemplates() {
    return ImmutableList.copyOf(templatesByName.values());
  }

  /**
   * Indicates that a template was visited.
   * @see #wasTemplateChecked
   */
  public void recordTemplateChecked(String templateName) {
    templatesChecked.add(templateName);
  }

  /**
   * True if {@link #recordTemplateChecked} was called with the same template name.
   */
  public boolean wasTemplateChecked(String templateName) {
    return templatesChecked.contains(templateName);
  }

  /**
   * The id generator used for newly created nodes.
   */
  public IdGenerator getIdGenerator() {
    return idGen;
  }
}
