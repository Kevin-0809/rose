package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LayoutTemplateTest {

    @Test
    void sidebarGroupsEveryExistingRoute() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/fragments/layout.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(hasClassToken(html, "aside", "app-sidebar")).isTrue();
        assertThat(html).contains("class=\"sidebar-close\"")
                .contains("aria-label=\"关闭菜单\"");
        assertThat(html).contains("th:attr=\"aria-current=${active == 'home'} ? 'page' : null\"");
        assertThat(hasClassToken(html, "section", "nav-group")).isTrue();
        assertThat(html).contains("数据准备");
        assertThat(html).contains("执行分析");
        assertThat(html).contains("运维工具");
        assertThat(html).contains("href=\"/\"");

        assertThat(sectionForGroup(html, "setup"))
                .contains("/config/import")
                .contains("/config/trans")
                .contains("/config/fields")
                .contains("/config/recording");
        assertThat(sectionForGroup(html, "analysis"))
                .contains("/sampling/commands")
                .contains("/sampling/summaries")
                .contains("/samples/transaction-diffs")
                .contains("/samples/field-diffs");
        assertThat(sectionForGroup(html, "operations"))
                .contains("/messages/flow-logs")
                .contains("/messages/flow-logs/new")
                .contains("/migration/commands")
                .contains("/migration/sql-commands");
        assertThat(hasClassToken(html, "nav", "nav")).isFalse();
        assertThat(html).contains("shell.classList.remove('sidebar-open')")
                .contains("sidebarToggle?.setAttribute('aria-expanded', 'false')")
                .contains("classList.toggle('is-expanded', expanded)")
                .contains("const syncSidebarAccessibility = () => {")
                .contains("const mobile = window.matchMedia('(max-width: 720px)').matches;")
                .contains("sidebar.toggleAttribute('inert', mobile && !open)")
                .contains("sidebar.setAttribute('aria-hidden', String(mobile && !open))")
                .contains("sidebarClose?.focus()")
                .contains("sidebarToggle?.focus()")
                .contains("event.key === 'Escape'")
                .contains("window.addEventListener('resize', syncSidebarAccessibility)");
    }

    private boolean hasClassToken(String html, String tagName, String className) {
        return Pattern.compile(
                "<" + tagName + "\\b(?:\\s+[^\\s=>]+(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?)*\\s+class\\s*=\\s*\"[^\"]*\\b" + Pattern.quote(className) + "\\b[^\"]*\"",
                Pattern.DOTALL
        ).matcher(html).find();
    }

    private String sectionForGroup(String html, String group) {
        int groupStart = html.indexOf("data-group=\"" + group + "\"");
        int sectionEnd = html.indexOf("</section>", groupStart);
        return html.substring(groupStart, sectionEnd + "</section>".length());
    }

    @Test
    void pagedTemplatesDoNotSubmitDuplicatePageParameters() throws Exception {
        List<String> templates = List.of(
                "/templates/config/fields.html",
                "/templates/config/recording.html",
                "/templates/config/trans.html",
                "/templates/samples/field-diffs.html",
                "/templates/samples/transaction-diffs.html",
                "/templates/sampling/commands.html",
                "/templates/sampling/summaries.html"
        );

        for (String template : templates) {
            String html = new String(getClass().getResourceAsStream(template).readAllBytes(), StandardCharsets.UTF_8);

            assertThat(html)
                    .as(template)
                    .contains("fragments/layout :: pager")
                    .doesNotContain("name=\"page\" value=\"1\"");
        }
    }

    @Test
    void pagesComposeTheSidebarAndWorkspaceWithoutCrossFragmentMarkup() throws Exception {
        List<String> templates = List.of(
                "/templates/home.html",
                "/templates/config/import.html",
                "/templates/config/import-list-progress.html",
                "/templates/config/trans.html",
                "/templates/config/fields.html",
                "/templates/config/recording.html",
                "/templates/sampling/commands.html",
                "/templates/sampling/summaries.html",
                "/templates/samples/transaction-diffs.html",
                "/templates/samples/field-diffs.html",
                "/templates/messages/flow-logs.html",
                "/templates/messages/flow-log-entry.html",
                "/templates/migration/commands.html",
                "/templates/migration/sql-commands.html",
                "/templates/migration/progress.html"
        );

        for (String template : templates) {
            String html = new String(getClass().getResourceAsStream(template).readAllBytes(), StandardCharsets.UTF_8);

            assertThat(html)
                    .as(template)
                    .contains("<div class=\"app-shell\" th:attr=\"data-active=${active}\">")
                    .contains("fragments/layout :: sidebar(${active})")
                    .contains("<div class=\"app-workspace\">")
                    .contains("fragments/layout :: workspaceBar")
                    .contains("fragments/layout :: sidebarScript")
                    .doesNotContain("fragments/layout :: topbar");
        }
    }
}
