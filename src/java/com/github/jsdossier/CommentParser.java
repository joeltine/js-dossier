/*
 Copyright 2013-2015 Jason Leyba

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.github.jsdossier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.github.jsdossier.proto.Comment;
import com.github.jsdossier.proto.TypeLink;
import com.google.common.collect.ImmutableList;
import com.google.common.escape.CharEscaperBuilder;
import com.google.common.escape.Escaper;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.html.HtmlRenderer;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/**
 * Utility class for working with JSDoc comments.
 */
public class CommentParser {

  private static Escaper HTML_ESCAPER = new CharEscaperBuilder()
      .addEscape('"', "&quot;")
      .addEscape('\'', "&#39;")
      .addEscape('&', "&amp;")
      .addEscape('<', "&lt;")
      .addEscape('>', "&gt;")
      .toEscaper();

  private static final Pattern SUMMARY_REGEX = Pattern.compile("(.*?\\.)[\\s$]", Pattern.DOTALL);
  private static final Pattern TAGLET_START_PATTERN = Pattern.compile("\\{@(\\w+)\\s");

  private final List<? extends Extension> extensions = ImmutableList.of(TablesExtension.create());
  private final Parser parser = Parser.builder()
      .extensions(extensions)
      .build();
  private final HtmlRenderer renderer = HtmlRenderer.builder()
      .escapeHtml(false)
      .extensions(extensions)
      .build();

  /**
   * Extracts summary sentence from the provided comment text. This is the substring up to the
   * first period (.) followed by a blank, tab, or newline.
   */
  public Comment getSummary(String text, Linker linker) {
    Matcher matcher = SUMMARY_REGEX.matcher(text);
    if (matcher.find()) {
      return parseComment(matcher.group(1), linker);
    }
    return parseComment(text, linker);
  }
  public Comment getSummary(String text, LinkFactory factory) {
    Matcher matcher = SUMMARY_REGEX.matcher(text);
    if (matcher.find()) {
      return parseComment(matcher.group(1), factory);
    }
    return parseComment(text, factory);
  }

  /**
   * Extracts the block comment string from the given type.
   */
  public Comment getBlockDescription(Linker linker, NominalType type) {
    try {
      linker.pushContext(type);
      return parseComment(type.getJsdoc().getBlockComment(), linker);
    } finally {
      linker.popContext();
    }
  }

  /**
   * Parses the {@code text} of a JSDoc block comment.
   */
  public Comment parseComment(String text, Linker linker) {
    String html = parseMarkdown(text, new LinkerLinkFactoryFacade(linker));
    if (html.isEmpty()) {
      return Comment.getDefaultInstance();
    }
    return Comment.newBuilder()
        .addToken(Comment.Token.newBuilder().setHtml(html))
        .build();
  }
  public Comment parseComment(String text, LinkFactory factory) {
    String html = parseMarkdown(text, new WrappedLinkFactory(factory));
    if (html.isEmpty()) {
      return Comment.getDefaultInstance();
    }
    return Comment.newBuilder()
        .addToken(Comment.Token.newBuilder().setHtml(html))
        .build();
  }

  private String parseMarkdown(String text, LinkFactoryFacade linkFactory) {
    if (isNullOrEmpty(text)) {
      return "";
    }

    StringBuilder builder = new StringBuilder(text.length());

    int start = 0;
    while (true) {
      int tagletStart = findInlineTagStart(text, start);
      if (tagletStart == -1) {
        if (start < text.length()) {
          builder.append(text.substring(start));
        }
        break;
      } else if (tagletStart > start) {
        builder.append(text.substring(start, tagletStart));
      }

      int tagletEnd = findInlineTagEnd(text, tagletStart + 1);
      if (tagletEnd == -1) {
        builder.append(text.substring(start));
        break;
      }

      String tagletName = getTagletName(text, tagletStart);
      String tagletPrefix = "{@" + tagletName + " ";
      String tagletText = text.substring(tagletStart + tagletPrefix.length(), tagletEnd);
      switch (tagletName) {
        case "code":
          builder.append("<code>").append(HTML_ESCAPER.escape(tagletText)).append("</code>");
          break;

        case "link":
        case "linkplain":
          LinkInfo info = LinkInfo.fromText(tagletText);
          @Nullable TypeLink link = linkFactory.createLink(info.type);

          String linkText = info.text;
          if ("link".equals(tagletName)) {
            linkText = "<code>" + linkText + "</code>";
          }
          if (link == null || link.getHref().isEmpty()) {
            builder.append(linkText);
          } else {
            builder.append("<a href=\"").append(link.getHref()).append("\">")
                .append(linkText)
                .append("</a>");
          }
          break;

        case "literal":
        default:
          builder.append(HTML_ESCAPER.escape(tagletText));
      }
      start = tagletEnd + 1;
    }

    String markdown = builder.toString();
    Node root = parser.parse(markdown);
    return renderer.render(root);
  }

  private static int findInlineTagStart(String text, int start) {
    Matcher matcher = TAGLET_START_PATTERN.matcher(text);
    if (!matcher.find(start)) {
      return -1;
    } else if (text.indexOf('}', matcher.start()) == -1) {
      return -1;
    } else {
      return matcher.start();
    }
  }

  private static String getTagletName(String text, int start) {
    Matcher matcher = TAGLET_START_PATTERN.matcher(text);
    checkArgument(matcher.find(start));
    return matcher.group(1);
  }

  private static int findInlineTagEnd(String text, int start) {
    int end = text.indexOf('}', start);
    if (end == -1) {
      return -1;
    }

    int nestedOpen = text.indexOf('{', start);
    if (nestedOpen != -1 && nestedOpen < end) {
      int nestedClose = findInlineTagEnd(text, nestedOpen + 1);
      if (nestedClose == -1) {
        return -1;
      }
      return findInlineTagEnd(text, nestedClose + 1);
    }

    return end;
  }
  
  // TODO: remove this with the old type system.
  private interface LinkFactoryFacade {
    @Nullable
    @CheckReturnValue
    TypeLink createLink(String symbol);
  }
  
  private static class LinkerLinkFactoryFacade implements LinkFactoryFacade {
    
    private final Linker linker;

    private LinkerLinkFactoryFacade(Linker linker) {
      this.linker = linker;
    }

    @Nullable
    @CheckReturnValue
    @Override
    public TypeLink createLink(String symbol) {
      return linker.getLink(symbol);
    }
  }
  
  private static class WrappedLinkFactory implements LinkFactoryFacade {
    
    private final LinkFactory factory;

    private WrappedLinkFactory(LinkFactory factory) {
      this.factory = factory;
    }

    @Nullable
    @CheckReturnValue
    @Override
    public TypeLink createLink(String symbol) {
      return factory.createLink(symbol);
    }
  }

  private static class LinkInfo {

    private final String type;
    private final String text;

    private LinkInfo(String type, String text) {
      this.type = type;
      this.text = text;
    }

    private static Pattern SEPARATOR = Pattern.compile("\\s");

    static LinkInfo fromText(String text) {
      String linkedType = text;
      String linkText = text;
      Matcher matcher = SEPARATOR.matcher(text);
      if (matcher.find()) {
        int index = matcher.start();
        linkedType = text.substring(0, matcher.start());
        linkText = text.substring(index + 1);
      }
      return new LinkInfo(linkedType, linkText);
    }
  }
}
