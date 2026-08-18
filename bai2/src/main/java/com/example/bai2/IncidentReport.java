package com.example.bai2;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "incident_report")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class IncidentReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String driverName;
    private String vehicleNumber;
    private String location;
    private String description;
}
