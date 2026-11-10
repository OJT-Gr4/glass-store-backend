package com.glassstore.lab.service;

import com.glassstore.lab.client.CatalogLocationClient;
import com.glassstore.lab.client.PrescriptionCustomerClient;
import com.glassstore.lab.controller.EyeExamAppointmentController;
import com.glassstore.lab.dto.CreateAppointmentRequest;
import com.glassstore.lab.dto.CreateLabQcRequest;
import com.glassstore.lab.dto.UpdateAppointmentStatusRequest;
import com.glassstore.lab.entity.EyeExamAppointment;
import com.glassstore.lab.entity.LabJob;
import com.glassstore.lab.entity.LabJobStatus;
import com.glassstore.lab.entity.LabQcHistory;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.entity.QcResult;
import com.glassstore.lab.repository.EyeExamAppointmentRepository;
import com.glassstore.lab.repository.LabJobHistoryRepository;
import com.glassstore.lab.repository.LabJobRepository;
import com.glassstore.lab.repository.LabQcHistoryRepository;
import com.glassstore.lab.repository.PrescriptionRepository;
import com.glassstore.lab.service.impl.LabJobServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabAndAppointmentNotificationTest {

    @Mock
    private LabJobRepository labJobRepository;

    @Mock
    private PrescriptionRepository prescriptionRepository;

    @Mock
    private LabJobHistoryRepository historyRepository;

    @Mock
    private LabQcHistoryRepository qcHistoryRepository;

    @Mock
    private PrescriptionService prescriptionService;

    @Mock
    private LabNotificationPublisher labNotificationPublisher;

    @InjectMocks
    private LabJobServiceImpl labJobService;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitQcPass_publishesLabReadyWithCustomerFromPrescription() {
        labJobService.setLabNotificationPublisher(labNotificationPublisher);
        LabJob job = LabJob.builder()
                .id(15L)
                .orderId(100L)
                .orderCode("ORD-100")
                .prescriptionId(200L)
                .customerName("Nguyễn Văn Duy")
                .status(LabJobStatus.IN_PROGRESS)
                .build();
        Prescription rx = Prescription.builder().id(200L).customerId(5L).optometristId(3L).build();

        when(labJobRepository.findById(15L)).thenReturn(Optional.of(job));
        when(labJobRepository.save(any(LabJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(qcHistoryRepository.save(any(LabQcHistory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptionRepository.findById(200L)).thenReturn(Optional.of(rx));

        labJobService.submitQcEvaluation(15L, CreateLabQcRequest.builder()
                .result(QcResult.PASS)
                .notes("OK")
                .build());

        verify(labNotificationPublisher).publishLabReady(job, 5L);
    }

    @Test
    void submitQcFail_publishesLabReworkWithoutCustomerLeak() {
        labJobService.setLabNotificationPublisher(labNotificationPublisher);
        LabJob job = LabJob.builder()
                .id(16L)
                .orderId(101L)
                .orderCode("ORD-101")
                .status(LabJobStatus.IN_PROGRESS)
                .reworkCount(0)
                .build();

        when(labJobRepository.findById(16L)).thenReturn(Optional.of(job));
        when(labJobRepository.save(any(LabJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(qcHistoryRepository.save(any(LabQcHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        labJobService.submitQcEvaluation(16L, CreateLabQcRequest.builder()
                .result(QcResult.FAIL)
                .failureReason("Sai trục loạn")
                .build());

        verify(labNotificationPublisher).publishLabRework(eq(job));
    }

    @Test
    void appointmentCreateAndStatusTransition_publishesNotificationEvents() {
        EyeExamAppointmentRepository appointments = mock(EyeExamAppointmentRepository.class);
        PrescriptionRepository rxRepo = mock(PrescriptionRepository.class);
        PrescriptionCustomerClient customers = mock(PrescriptionCustomerClient.class);
        CatalogLocationClient locations = mock(CatalogLocationClient.class);
        LabNotificationPublisher publisher = mock(LabNotificationPublisher.class);

        EyeExamAppointmentController controller = new EyeExamAppointmentController(appointments, rxRepo, customers, locations);
        controller.setLabNotificationPublisher(publisher);

        Jwt jwt = Jwt.withTokenValue("tok")
                .header("alg", "none")
                .subject("5")
                .claim("userId", 5L)
                .claim("roles", List.of("ROLE_CUSTOMER"))
                .claim("fullName", "Duy")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(() -> "ROLE_CUSTOMER")));

        when(customers.requireCustomer(5L)).thenReturn("Duy");
        when(appointments.saveAndFlush(any(EyeExamAppointment.class))).thenAnswer(inv -> {
            EyeExamAppointment a = inv.getArgument(0);
            if (a.getId() == null) a.setId(42L);
            return a;
        });

        CreateAppointmentRequest createReq = new CreateAppointmentRequest();
        createReq.setCustomerPhone("0901234567");
        createReq.setScheduledTime(LocalDateTime.now().plusDays(1).withNano(0).toString());

        controller.createAppointment(createReq);

        Jwt optJwt = Jwt.withTokenValue("opt-tok")
                .header("alg", "none")
                .subject("3")
                .claim("userId", 3L)
                .claim("roles", List.of("ROLE_OPTOMETRIST"))
                .claim("fullName", "KTV Giang")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(optJwt, List.of(() -> "ROLE_OPTOMETRIST")));

        EyeExamAppointment existing = EyeExamAppointment.builder()
                .id(42L).version(0L).customerId(5L).customerName("Duy")
                .ticketCode("EP-42").status("WAITING").build();
        when(appointments.findById(42L)).thenReturn(Optional.of(existing));

        UpdateAppointmentStatusRequest statusReq = new UpdateAppointmentStatusRequest();
        statusReq.setExpectedVersion(0L);
        statusReq.setStatus("IN_EXAM");
        statusReq.setRoomName("Phòng 01");

        controller.updateStatus(42L, statusReq);

        org.mockito.ArgumentCaptor<EyeExamAppointment> captor = org.mockito.ArgumentCaptor.forClass(EyeExamAppointment.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishAppointmentEvent(captor.capture());
        List<EyeExamAppointment> published = captor.getAllValues();
        org.assertj.core.api.Assertions.assertThat(published).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(published.get(0).getCustomerId()).isEqualTo(5L);
        org.assertj.core.api.Assertions.assertThat(published.get(1).getStatus()).isEqualTo("IN_EXAM");
    }
}
