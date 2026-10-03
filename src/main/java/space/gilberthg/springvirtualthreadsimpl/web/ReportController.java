package space.gilberthg.springvirtualthreadsimpl.web;

import space.gilberthg.springvirtualthreadsimpl.model.Report;
import space.gilberthg.springvirtualthreadsimpl.service.ReportService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public Report report() {
        return reportService.load();
    }

}
