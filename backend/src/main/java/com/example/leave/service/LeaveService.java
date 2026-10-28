package com.example.leave.service;

import com.example.leave.api.DecisionRequest;
import com.example.leave.api.SubmitLeaveRequest;
import com.example.leave.domain.LeaveBalance;
import com.example.leave.domain.LeaveBalanceRepository;
import com.example.leave.domain.LeaveRequest;
import com.example.leave.domain.LeaveRequestRepository;
import com.example.leave.domain.LeaveStatus;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.UserTask;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveService.class);

    /** Example yearly annual-leave entitlement. */
    static final int DEFAULT_ENTITLEMENT = 20;

    private final LeaveRequestRepository requests;
    private final LeaveBalanceRepository balances;
    private final CamundaClient camunda;

    public LeaveService(
            LeaveRequestRepository requests,
            LeaveBalanceRepository balances,
            CamundaClient camunda) {
        this.requests = requests;
        this.balances = balances;
        this.camunda = camunda;
    }

    /**
     * Saves the request, makes sure a balance exists and starts the process.
     * Not @Transactional on purpose: the request must be committed before the
     * process starts, because the validate-leave worker may run immediately.
     */
    public LeaveRequest submit(SubmitLeaveRequest body) {
        LeaveRequest request = new LeaveRequest();
        request.setEmployeeName(body.employeeName());
        request.setEmployeeEmail(body.employeeEmail());
        request.setLeaveType(body.leaveType().toUpperCase());
        request.setStartDate(body.startDate());
        request.setEndDate(body.endDate());
        request.setReason(body.reason());
        request.setDays(countWeekdays(body.startDate(), body.endDate()));
        request.setStatus(LeaveStatus.SUBMITTED);
        request = requests.save(request);

        balance(body.employeeEmail());

        Map<String, Object> variables = new HashMap<>();
        variables.put("requestId", request.getId());
        variables.put("employeeEmail", request.getEmployeeEmail());
        variables.put("leaveType", request.getLeaveType());
        variables.put("days", request.getDays());
        variables.put("startDate", request.getStartDate().toString());
        variables.put("endDate", request.getEndDate().toString());

        ProcessInstanceEvent instance = camunda
                .newCreateInstanceCommand()
                .bpmnProcessId("leave-request")
                .latestVersion()
                .variables(variables)
                .send()
                .join();

        requests.setProcessInstanceKey(request.getId(), instance.getProcessInstanceKey());
        return requests.findById(request.getId()).orElse(request);
    }

    /** Completes the Manager approval user task of the request's process instance. */
    public void decide(Long id, DecisionRequest decision) {
        LeaveRequest request = requests.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));

        if (request.getStatus() != LeaveStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is not awaiting approval");
        }
        if (request.getProcessInstanceKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Process instance is not linked yet");
        }

        List<UserTask> tasks = camunda
                .newUserTaskSearchRequest()
                .filter(f -> f
                        .processInstanceKey(request.getProcessInstanceKey())
                        .state(UserTaskState.CREATED))
                .send()
                .join()
                .items();

        if (tasks.isEmpty()) {
            // The task may not be indexed yet; the React client retries on 409.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval task not available yet");
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("approved", decision.approved());
        variables.put("managerComment", decision.comment() == null ? "" : decision.comment());

        camunda.newCompleteUserTaskCommand(tasks.get(0).getUserTaskKey())
                .variables(variables)
                .send()
                .join();
    }

    /**
     * Called by the validate-leave worker. Rejects invalid periods and annual
     * requests above the remaining balance; otherwise marks the request as
     * pending manager approval.
     */
    @Transactional
    public Map<String, Object> validate(Long requestId) {
        LeaveRequest request = requests.findById(requestId).orElse(null);
        if (request == null) {
            return result(false, "Leave request not found");
        }

        String problem = null;
        if (request.getEndDate().isBefore(request.getStartDate())) {
            problem = "End date is before the start date";
        } else if (request.getDays() <= 0) {
            problem = "The selected period contains no working days";
        } else if ("ANNUAL".equals(request.getLeaveType())
                && balance(request.getEmployeeEmail()).getRemainingDays() < request.getDays()) {
            problem = "Insufficient annual leave balance";
        }

        if (problem != null) {
            request.setStatusMessage(problem);
            requests.save(request);
            return result(false, problem);
        }

        request.setStatus(LeaveStatus.PENDING_APPROVAL);
        request.setStatusMessage("Waiting for manager approval");
        requests.save(request);
        return result(true, "OK");
    }

    /**
     * Called by the finalize-leave worker. Idempotent: a retried job cannot
     * deduct annual leave twice.
     */
    @Transactional
    public void finalizeRequest(Long id, boolean approved, String managerComment, String validationMessage) {
        LeaveRequest request = requests.findById(id).orElse(null);
        if (request == null) {
            log.warn("finalize-leave: request {} not found", id);
            return;
        }
        if (request.getStatus() == LeaveStatus.APPROVED || request.getStatus() == LeaveStatus.REJECTED) {
            log.info("finalize-leave: request {} already final ({}), skipping", id, request.getStatus());
            return;
        }

        if (approved) {
            if ("ANNUAL".equals(request.getLeaveType())) {
                LeaveBalance balance = balance(request.getEmployeeEmail());
                balance.setRemainingDays(balance.getRemainingDays() - request.getDays());
                balances.save(balance);
            }
            request.setStatus(LeaveStatus.APPROVED);
            request.setStatusMessage(isBlank(managerComment) ? "Approved" : managerComment);
        } else {
            request.setStatus(LeaveStatus.REJECTED);
            if (!isBlank(managerComment)) {
                request.setStatusMessage(managerComment);
            } else if (!isBlank(validationMessage) && !"OK".equals(validationMessage)) {
                request.setStatusMessage(validationMessage);
            } else {
                request.setStatusMessage("Rejected by manager");
            }
        }
        requests.save(request);
        notifyEmployee(request);
    }

    /** Returns the balance, creating the default entitlement if none exists. */
    @Transactional
    public LeaveBalance balance(String email) {
        return balances.findById(email)
                .orElseGet(() -> balances.save(new LeaveBalance(email, DEFAULT_ENTITLEMENT)));
    }

    /** Notification hook: replace the log line with e-mail, chat, etc. */
    private void notifyEmployee(LeaveRequest request) {
        log.info("Notify {}: leave request #{} is {} ({})",
                request.getEmployeeEmail(), request.getId(),
                request.getStatus(), request.getStatusMessage());
    }

    static int countWeekdays(LocalDate start, LocalDate end) {
        int count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            DayOfWeek day = d.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Object> result(boolean valid, String message) {
        Map<String, Object> out = new HashMap<>();
        out.put("valid", valid);
        out.put("validationMessage", message);
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
