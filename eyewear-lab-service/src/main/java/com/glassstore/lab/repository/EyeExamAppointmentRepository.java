package com.glassstore.lab.repository;

import com.glassstore.lab.entity.EyeExamAppointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EyeExamAppointmentRepository extends JpaRepository<EyeExamAppointment, Long> {
    Optional<EyeExamAppointment> findByTicketCode(String ticketCode);
    List<EyeExamAppointment> findAllByOrderByPriorityLevelAscCreatedAtAsc();
    boolean existsByLocationCodeIgnoreCase(String locationCode);
}
