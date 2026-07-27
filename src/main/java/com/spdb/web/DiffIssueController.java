package com.spdb.web;

import com.spdb.report.DiffIssueLedgerService;
import com.spdb.report.DiffIssueRow;
import com.spdb.report.DiffIssueSearch;
import com.spdb.report.DiffIssueUpdate;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Controller
public class DiffIssueController {
    private final DiffIssueLedgerService service;

    public DiffIssueController(DiffIssueLedgerService service) {
        this.service = service;
    }

    @GetMapping("/diff-issues")
    public String list(@RequestParam(required = false) String issueLevel,
                       @RequestParam(required = false) String issueStatus,
                       @RequestParam(required = false) String serviceCode,
                       @RequestParam(required = false) String moduleName,
                       @RequestParam(required = false) String transactionOwner,
                       @RequestParam(required = false) LocalDate firstSeenFrom,
                       @RequestParam(required = false) LocalDate firstSeenTo,
                       @RequestParam(required = false) LocalDate lastSeenFrom,
                       @RequestParam(required = false) LocalDate lastSeenTo,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        PageRequestParams pageParams = PageRequestParams.of(page, size);
        DiffIssueSearch search = new DiffIssueSearch(issueLevel, issueStatus, serviceCode, moduleName, transactionOwner,
                firstSeenFrom, firstSeenTo, lastSeenFrom, lastSeenTo, keyword);
        model.addAttribute("active", "diff-issues");
        model.addAttribute("result", service.searchPaged(search, pageParams));
        model.addAttribute("issueLevel", issueLevel);
        model.addAttribute("issueStatus", issueStatus);
        model.addAttribute("serviceCode", serviceCode);
        model.addAttribute("moduleName", moduleName);
        model.addAttribute("transactionOwner", transactionOwner);
        model.addAttribute("firstSeenFrom", firstSeenFrom);
        model.addAttribute("firstSeenTo", firstSeenTo);
        model.addAttribute("lastSeenFrom", lastSeenFrom);
        model.addAttribute("lastSeenTo", lastSeenTo);
        model.addAttribute("keyword", keyword);
        return "diff-issues/list";
    }

    @GetMapping("/diff-issues/{id}")
    public String detail(@PathVariable long id, Model model) {
        DiffIssueRow issue = service.findById(id);
        if (issue == null) {
            return "redirect:/diff-issues";
        }
        model.addAttribute("active", "diff-issues");
        model.addAttribute("issue", issue);
        return "diff-issues/detail";
    }

    @PostMapping("/diff-issues/{id}")
    public String update(@PathVariable long id,
                         @RequestParam(required = false) String problemType,
                         @RequestParam(required = false) String preliminaryAnalysis,
                         @RequestParam(required = false) String finalSolution,
                         @RequestParam String issueStatus,
                         @RequestParam(required = false) String coordinationRequired,
                         @RequestParam(required = false) String resolver,
                         @RequestParam(required = false) LocalDate resolutionDate,
                         @RequestParam(required = false) LocalDate defectFixDate,
                         @RequestParam String updatedAt,
                         RedirectAttributes flash) {
        try {
            service.update(id, new DiffIssueUpdate(problemType, preliminaryAnalysis, finalSolution, issueStatus,
                    coordinationRequired, resolver, resolutionDate, defectFixDate), LocalDateTime.parse(updatedAt));
            flash.addFlashAttribute("message", "问题台账已保存。");
        } catch (OptimisticLockingFailureException exception) {
            flash.addFlashAttribute("error", "该问题已被其他人更新，请刷新后再保存。");
        } catch (IllegalArgumentException exception) {
            flash.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/diff-issues/" + id;
    }
}
