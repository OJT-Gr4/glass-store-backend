package com.glassstore.lab.service;

import com.glassstore.lab.dto.CreateLabJobRequest;
import com.glassstore.lab.dto.CreateLabQcRequest;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.dto.LabQcResponse;
import com.glassstore.lab.dto.ReworkJobRequest;
import com.glassstore.lab.dto.UpdateJobStatusRequest;
import com.glassstore.lab.entity.LabJobStatus;

import java.util.List;

public interface LabJobService {

    List<LabJobResponse> getLabJobs(LabJobStatus status);

    LabJobResponse getLabJobById(Long id);

    LabJobResponse updateJobStatus(Long id, UpdateJobStatusRequest request);

    LabJobResponse createLabJob(CreateLabJobRequest request);

    LabJobResponse createFromOrder(
            Long orderId,
            Long prescriptionId,
            String frameSku,
            String frameName,
            String lensSku,
            String lensName,
            String technicianName);

    LabQcResponse submitQcEvaluation(Long id, CreateLabQcRequest request);

    LabJobResponse requestRework(Long id, ReworkJobRequest request);

    List<LabQcResponse> getQcHistory(Long id);
}
