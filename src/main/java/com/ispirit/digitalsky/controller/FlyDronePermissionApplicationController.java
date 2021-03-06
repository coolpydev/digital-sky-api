package com.ispirit.digitalsky.controller;

import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ispirit.digitalsky.document.FlightLogObject;
import com.ispirit.digitalsky.document.FlyDronePermissionApplication;
import com.ispirit.digitalsky.domain.*;
import com.ispirit.digitalsky.dto.Errors;
import com.ispirit.digitalsky.exception.*;
import com.ispirit.digitalsky.service.api.FlightLogService;
import com.ispirit.digitalsky.service.api.FlyDronePermissionApplicationService;
import com.ispirit.digitalsky.service.api.OperatorDroneService;
import com.ispirit.digitalsky.service.api.UserProfileService;
import com.ispirit.digitalsky.util.CustomValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static com.cronutils.model.CronType.QUARTZ;
import static com.ispirit.digitalsky.controller.FlyDronePermissionApplicationController.APPLICATION_RESOURCE_BASE_PATH;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

@RestController
@RequestMapping(APPLICATION_RESOURCE_BASE_PATH)
public class FlyDronePermissionApplicationController {

    public static final String APPLICATION_RESOURCE_BASE_PATH = "/api/applicationForm/flyDronePermissionApplication";

    private FlyDronePermissionApplicationService service;
    private OperatorDroneService operatorDroneService;
    private UserProfileService userProfileService;
    private CustomValidator validator;
    private ObjectMapper objectMapper;
    private FlightLogService flightLogService;

    @Autowired
    public FlyDronePermissionApplicationController(FlyDronePermissionApplicationService service, OperatorDroneService operatorDroneService, UserProfileService userProfileService, CustomValidator validator, ObjectMapper objectMapper, FlightLogService flightLogService) {
        this.service = service;
        this.operatorDroneService = operatorDroneService;
        this.userProfileService = userProfileService;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.flightLogService = flightLogService;
    }

    @RequestMapping(method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createApplication(@RequestBody FlyDronePermissionApplication application) {
        validateDroneId(application.getDroneId());
        if (application.isSubmitted()) {
            validator.validate(application);
            validateRecurrenceTimePattern(application);
        }
        FlyDronePermissionApplication savedApplication = service.createApplication(application);
        return new ResponseEntity<>(savedApplication, HttpStatus.CREATED);
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.PATCH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateApplication(@PathVariable String id, @RequestBody FlyDronePermissionApplication application) {

        validateDroneId(application.getDroneId());

        UserPrincipal userPrincipal = UserPrincipal.securityContext();
        FlyDronePermissionApplication currentApplication = service.get(id);

        if (currentApplication == null) throw new EntityNotFoundException("FlyDronePermissionApplication", id);

        if (userPrincipal.getId() != currentApplication.getApplicantId()) {
            return new ResponseEntity<>(new Errors("UnAuthorized Access"), HttpStatus.UNAUTHORIZED);
        }

        if (!currentApplication.canBeModified()) {
            return new ResponseEntity<>(new Errors("Application not in draft status, cannot be modified"), HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (application.isSubmitted()) {
            validator.validate(application);
            validateRecurrenceTimePattern(application);
        }
        FlyDronePermissionApplication savedApplication = service.updateApplication(id, application);
        return new ResponseEntity<>(savedApplication, HttpStatus.OK);

    }


    @RequestMapping(value = "/approve/{id}", method = RequestMethod.PATCH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> approveApplication(@PathVariable String id, @Valid @RequestBody ApproveRequestBody approveRequestBody) {
        try {
            FlyDronePermissionApplication application = service.approveApplication(approveRequestBody);
            return new ResponseEntity<>(application, HttpStatus.OK);
        } catch (ApplicationNotFoundException e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.NOT_FOUND);
        } catch (UnAuthorizedAccessException e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.UNAUTHORIZED);
        } catch (ApplicationNotInSubmittedStatusException e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/approveByAtc/{id}", method = RequestMethod.PATCH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ATC_ADMIN')")
    public ResponseEntity<?> approveApplicationByAtc(@PathVariable String id, @Valid @RequestBody ApproveRequestBody approveRequestBody) {
        try {
            FlyDronePermissionApplication application = service.approveByAtcApplication(approveRequestBody);
            return new ResponseEntity<>(application, HttpStatus.OK);
        } catch (ApplicationNotFoundException e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.NOT_FOUND);
        } catch (UnAuthorizedAccessException e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.UNAUTHORIZED);
        } catch (ApplicationNotInSubmittedStatusException e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/approveByAfmlu/{id}", method = RequestMethod.PATCH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('AFMLU_ADMIN')")
    public ResponseEntity<?> approveApplicationByAfmlu(@PathVariable String id, @Valid @RequestBody ApproveRequestBody approveRequestBody) {
        try {
            FlyDronePermissionApplication application = service.approveByAfmluApplication(approveRequestBody);
            return new ResponseEntity<>(application, HttpStatus.OK);
        } catch (ApplicationNotFoundException e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.NOT_FOUND);
        } catch (UnAuthorizedAccessException e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.UNAUTHORIZED);
        } catch (ApplicationNotInSubmittedStatusException e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/list", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listApplications(@RequestParam(required = false, value = "droneId") String droneIdString) {
        long droneId = 0;
        Collection<FlyDronePermissionApplication> applications;
        if (isNotEmpty(droneIdString)) {
            try {
                droneId = Long.parseLong(droneIdString);
            } catch (NumberFormatException e) {
                return new ResponseEntity<>(new Errors("Invalid Drone Id"), HttpStatus.BAD_REQUEST);
            }
        }
        if (droneId != 0) {
            validateDroneId(droneId);
            applications = service.getApplicationsOfDrone(droneId);
            return new ResponseEntity<>(applications, HttpStatus.OK);
        }
        return new ResponseEntity<>(new Errors("Invalid Drone Id"), HttpStatus.BAD_REQUEST);
    }

    @RequestMapping(value = "/{applicationId}/document/permissionArtifact", method = RequestMethod.GET, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<?> getFile(@PathVariable String applicationId) { //todo: write test for this

        try {
            FlyDronePermissionApplication application = service.get(applicationId);

            if(application==null)
                return new ResponseEntity<>(new Errors(new ApplicationNotFoundException().getMessage()),HttpStatus.BAD_REQUEST);

            UserPrincipal userPrincipal = UserPrincipal.securityContext();
            if (!userPrincipal.isAdmin() && userPrincipal.getId() != application.getApplicantId()) {
                return new ResponseEntity<>(new Errors("UnAuthorized Access"), HttpStatus.UNAUTHORIZED);
            }
            if (!application.getStatus().equals(ApplicationStatus.APPROVED)&& !application.getStatus().equals(ApplicationStatus.APPROVEDBYAFMLU)) {
                return new ResponseEntity<>(new Errors("Application Not Approved Yet"), HttpStatus.BAD_REQUEST);
            }
            Resource resourceFile = service.getPermissionArtifact(applicationId);

            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=" + resourceFile.getFilename()).contentLength(resourceFile.contentLength()).cacheControl(CacheControl.noCache()).body(resourceFile);
        } catch (Exception e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.NOT_FOUND);
        }
    }

    @RequestMapping(value = "/{applicationId}/document/flightLog", method = RequestMethod.POST, consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> postFlightLog(@PathVariable String applicationId,@RequestParam(value = "flightLogDocument") MultipartFile flightLogDocument)
    {
        try {
            FlyDronePermissionApplication application = service.get(applicationId);

            if (application == null)
                return new ResponseEntity<>(new Errors(new ApplicationNotFoundException().getMessage()), HttpStatus.BAD_REQUEST);

            UserPrincipal userPrincipal = UserPrincipal.securityContext();
            if (!userPrincipal.isAdmin() && userPrincipal.getId() != application.getApplicantId()) {
                return new ResponseEntity<>(new Errors("UnAuthorized Access"), HttpStatus.UNAUTHORIZED);
            }
            if (!application.getStatus().equals(ApplicationStatus.APPROVED) && !application.getStatus().equals(ApplicationStatus.APPROVEDBYAFMLU)) {
                return new ResponseEntity<>(new Errors("Application Not Approved Yet"), HttpStatus.BAD_REQUEST);
            }
            if(LocalDateTime.now().isBefore(application.getEndDateTime()))
                return new ResponseEntity<>(new Errors("Flight time is still not complete and log cannot be submitted"),HttpStatus.BAD_REQUEST);

            String content = new String(flightLogDocument.getBytes(),"UTF-8");

            FlightLogObject flightLogObject = objectMapper.readValue(content, FlightLogObject.class);

            FlightLogEntry flightLogEntry = new FlightLogEntry(application.getId(),application.getUin(),flightLogObject.getSignature());

            if(flightLogService.testAgainstPreviousHash(flightLogEntry))
                flightLogService.storeFlightLog(application,content,flightLogEntry);

            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(new Errors(e.getMessage()), HttpStatus.NOT_FOUND);
        }
    }

    @RequestMapping(value = "/getAll", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasRole('VIEWER_ADMIN')")
    public ResponseEntity<?> listAll() {

        Collection<?> applicationForms = service.getAllApplications();
        List<?> submittedApplications = applicationForms.stream().filter(applicationForm -> {
            ApplicationStatus status = ((FlyDronePermissionApplication) applicationForm).getStatus();
            return status != null && status != ApplicationStatus.DRAFT;
        }).collect(Collectors.toList());

        return new ResponseEntity<>(submittedApplications, HttpStatus.OK);
    }

    @RequestMapping(value = "/getAllAtc", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ATC_ADMIN') or hasRole('ATC_VIEWER_ADMIN')")
    public ResponseEntity<?> listAllFromRegionForAtcAdmin() {
        Collection<?> applicationForms = service.getAllApplications();
        List<?> submittedApplications = applicationForms.stream().filter(applicationForm -> {
            ApplicationStatus status = ((FlyDronePermissionApplication) applicationForm).getStatus();
            return status != null && (status==ApplicationStatus.SUBMITTED || status==ApplicationStatus.APPROVEDBYATC || status==ApplicationStatus.REJECTEDBYATC);
        }).collect(Collectors.toList());

        return new ResponseEntity<>(submittedApplications, HttpStatus.OK);
    }

    @RequestMapping(value = "/getAllAfmlu", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('AFMLU_ADMIN') or hasRole('AFMLU_VIEWER_ADMIN')")
    public ResponseEntity<?> listAllFromRegionForAfmluAdmin() {
        Collection<?> applicationForms = service.getAllApplications();
        List<?> submittedApplications = applicationForms.stream().filter(applicationForm -> {
            ApplicationStatus status = ((FlyDronePermissionApplication) applicationForm).getStatus();
            return status != null && (status==ApplicationStatus.APPROVEDBYATC || status==ApplicationStatus.APPROVEDBYAFMLU || status==ApplicationStatus.REJECTEDBYAFMLU);
        }).collect(Collectors.toList());

        return new ResponseEntity<>(submittedApplications, HttpStatus.OK);
    }

    private void validateDroneId(long droneId) {
        OperatorDrone operatorDrone = operatorDroneService.find(droneId);
        if (operatorDrone == null) {
            throw new ValidationException(new Errors("Invalid Drone Id"));
        }

        UserProfile profile = userProfileService.profile(UserPrincipal.securityContext().getId());
        if (!profile.owns(operatorDrone)) {
            throw new UnAuthorizedAccessException();
        }

        if (!operatorDrone.getOperatorDroneStatus().equals(OperatorDroneStatus.UIN_APPROVED)) {
            throw new ValidationException(new Errors("UIN not approved for drone"));
        }
    }

    private void validateRecurrenceTimePattern(FlyDronePermissionApplication application) {//todo: write test for this
        if (isEmpty(application.getRecurringTimeExpression())) return;

        try {
            CronParser cronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(QUARTZ));
            Cron cron = cronParser.parse(application.getRecurringTimeExpression());
            cron.validate();
        } catch (Exception e) {
            throw new ValidationException(new Errors("Invalid Recurring time expression"));
        }

        if (application.getRecurringTimeDurationInMinutes() == null || application.getRecurringTimeDurationInMinutes() <=0 ){
            throw new ValidationException(new Errors("Invalid Recurring time duration"));
        }
    }

}

