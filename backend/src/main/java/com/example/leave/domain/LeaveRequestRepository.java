package com.example.leave.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LeaveRequestRepository
        extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findByEmployeeEmailOrderByCreatedAtDesc(String employeeEmail);

    List<LeaveRequest> findByStatusOrderByCreatedAtAsc(LeaveStatus status);

    /**
     * Updates only the process instance key so that it can never overwrite a
     * status that a job worker has already changed in the meantime.
     */
    @Modifying
    @Transactional
    @Query("update LeaveRequest r set r.processInstanceKey = :key where r.id = :id")
    int setProcessInstanceKey(@Param("id") Long id, @Param("key") Long key);
}
