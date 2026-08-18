package com.example.bai2;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class IncidentReportService {
    private final ChatModel chatModel;
    private final IncidentReportRepository incidentReportRepository;

    public IncidentReport extractIncidentReport(String rawText) {
        BeanOutputConverter<IncidentReportExtraction> converter = new BeanOutputConverter<>(IncidentReportExtraction.class);
        String template = """
                Hãy giúp tôi phân tích yêu cầu sau {rawText} và chỉ trả về dữ liệu có định dạng JSON {format}
                Lưu ý: chỉ trả về định dạng JSON, sử dụng tiếng việt
                """;
        Prompt prompt = new PromptTemplate(template).create(
                Map.of(
                        "rawText", rawText,
                        "format", converter.getFormat()
                )
        );
        String response = chatModel.call(prompt).getResult().getOutput().getText();
        IncidentReportExtraction extraction = converter.convert(response);
        IncidentReport incidentReport = IncidentReport.builder()
                .driverName(extraction.driverName())
                .vehicleNumber(extraction.vehicleNumber())
                .location(extraction.location())
                .description(extraction.description())
                .build();

        return incidentReportRepository.save(incidentReport);
    }
}
