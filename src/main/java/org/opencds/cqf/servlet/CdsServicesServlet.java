package org.opencds.cqf.servlet;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.rp.dstu3.LibraryResourceProvider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.hl7.fhir.dstu3.model.*;
import org.hl7.fhir.exceptions.FHIRException;
import org.opencds.cqf.cds.*;
import org.opencds.cqf.helpers.Dstu2ToStu3;
import org.opencds.cqf.providers.FHIRPlanDefinitionResourceProvider;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Created by Christopher Schuler on 5/1/2017.
 */
@WebServlet(name = "cds-services")
public class CdsServicesServlet extends BaseServlet {

    // default is DSTU2
    private boolean isStu3 = false;

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        // validate that we are dealing with JSON
        if (!request.getContentType().equals("application/json")) {
            throw new ServletException(String.format("Invalid content type %s. Please use application/json.", request.getContentType()));
        }

        CdsHooksRequest cdsHooksRequest = new CdsHooksRequest(request.getReader());
        cdsHooksRequest.setService(request.getPathInfo().replace("/", ""));

        //
        if (cdsHooksRequest.getFhirServer() != null) {
            isStu3 = isStu3(cdsHooksRequest.getFhirServer());
        }
        CdsRequestProcessor processor = null;

        // necessary resource providers
        LibraryResourceProvider libraryResourceProvider = (LibraryResourceProvider) getProvider("Library");
        FHIRPlanDefinitionResourceProvider planDefinitionResourceProvider = (FHIRPlanDefinitionResourceProvider) getProvider("PlanDefinition");

        // Custom cds services - requires customized terminology/data providers
        if (request.getRequestURL().toString().endsWith("cdc-opioid-guidance")) {
            resolveMedicationPrescribePrefetch(cdsHooksRequest);
            try {
                processor = new OpioidGuidanceProcessor(cdsHooksRequest, libraryResourceProvider, planDefinitionResourceProvider, isStu3);
            } catch (FHIRException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        else if (request.getRequestURL().toString().endsWith("diabetes-management")) {
            try {
                resolveDiabetesManagementPrefetch(cdsHooksRequest);
                processor = new DiabetesManagementProcessor(cdsHooksRequest, libraryResourceProvider, planDefinitionResourceProvider, isStu3);
            } catch (FHIRException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        else {
            // User-defined cds services
            // These are limited - no user-defined data/terminology providers
            switch (cdsHooksRequest.getHook()) {
                case "medication-prescribe":
                    resolveMedicationPrescribePrefetch(cdsHooksRequest);
                    try {
                        processor = new MedicationPrescribeProcessor(cdsHooksRequest, libraryResourceProvider, planDefinitionResourceProvider, isStu3);
                    } catch (FHIRException e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                    break;
                case "order-review":
                    // resolveOrderReviewPrefetch(cdsHooksRequest);
                    // TODO - currently only works for ProcedureRequest orders
                    processor = new OrderReviewProcessor(cdsHooksRequest, libraryResourceProvider, planDefinitionResourceProvider, isStu3);
                    break;

                case "patient-view":
                    processor = new PatientViewProcessor(cdsHooksRequest, libraryResourceProvider, planDefinitionResourceProvider, isStu3);
                    break;
            }
        }

        if (processor == null) {
            throw new RuntimeException("Invalid cds service");
        }

        response.getWriter().println(toJsonResponse(processor.process()));
    }

    // This is a little bit of a hacky way to determine the FHIR version - but it works =)
    private boolean isStu3(String baseUrl) throws IOException {
        URL url = new URL(baseUrl + "/metadata");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        StringBuilder response = new StringBuilder();
        try(Reader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8")))
        {
            for (int i; (i = in.read()) >= 0;) {
                response.append((char) i);
            }
        }

        return !response.toString().contains("Conformance");
    }

    // If the EHR did not provide the prefetch resources, fetch them
    // Assuming EHR is using DSTU2 resources here...
    // This is a big drag on performance.
    private void resolveMedicationPrescribePrefetch(CdsHooksRequest cdsHooksRequest) {
        if (cdsHooksRequest.getPrefetch().size() == 0) {
            if (isStu3) {
                String searchUrl = String.format("MedicationRequest?patient=%s&status=active", cdsHooksRequest.getContextProperty("patientId"));
                Bundle postfetch = FhirContext.forDstu3()
                        .newRestfulGenericClient(cdsHooksRequest.getFhirServer())
                        .search()
                        .byUrl(searchUrl)
                        .returnBundle(Bundle.class)
                        .execute();
                cdsHooksRequest.setPrefetch(postfetch, "medication");
            }
            else {
                String searchUrl = String.format("MedicationOrder?patient=%s&status=active", cdsHooksRequest.getContextProperty("patientId"));
                org.hl7.fhir.instance.model.Bundle postfetch = FhirContext.forDstu2Hl7Org()
                        .newRestfulGenericClient(cdsHooksRequest.getFhirServer())
                        .search()
                        .byUrl(searchUrl)
                        .returnBundle(org.hl7.fhir.instance.model.Bundle.class)
                        .execute();
                cdsHooksRequest.setPrefetch(postfetch, "medication");
            }
        }
    }

    private Bundle getPrefetchElement(String searchUrl, String fhirServerEndpoint) throws FHIRException {
        Bundle postfetch;
        if (isStu3) {
            postfetch = FhirContext.forDstu3()
                    .newRestfulGenericClient(fhirServerEndpoint)
                    .search()
                    .byUrl(searchUrl)
                    .returnBundle(Bundle.class)
                    .execute();
        }
        else {
            postfetch = (Bundle) Dstu2ToStu3.convertResource(FhirContext.forDstu2Hl7Org()
                    .newRestfulGenericClient(fhirServerEndpoint)
                    .search()
                    .byUrl(searchUrl)
                    .returnBundle(org.hl7.fhir.instance.model.Bundle.class)
                    .execute());
        }
        return postfetch;
    }

    public void resolveDiabetesManagementPrefetch(CdsHooksRequest cdsHooksRequest) throws FHIRException {
        if (cdsHooksRequest.getPrefetch().size() == 0) {
            String searchUrl = String.format("Condition?patient=%s&code=250.00,E11.9,313436004,73211009", cdsHooksRequest.getContextProperty("patientId"));
            cdsHooksRequest.setPrefetch(getPrefetchElement(searchUrl, cdsHooksRequest.getFhirServer()), "DiabetesConditions");

            searchUrl = String.format("Observation?patient=%s&code=2160-0", cdsHooksRequest.getContextProperty("patientId"));
            cdsHooksRequest.setPrefetch(getPrefetchElement(searchUrl, cdsHooksRequest.getFhirServer()), "CreatinineLabs");

            searchUrl = String.format("Observation?patient=%s&code=59261-8", cdsHooksRequest.getContextProperty("patientId"));
            cdsHooksRequest.setPrefetch(getPrefetchElement(searchUrl, cdsHooksRequest.getFhirServer()), "HbA1CLabs");

            searchUrl = String.format("Observation?patient=%s&code=13457-7", cdsHooksRequest.getContextProperty("patientId"));
            cdsHooksRequest.setPrefetch(getPrefetchElement(searchUrl, cdsHooksRequest.getFhirServer()), "LDLLabs");

            searchUrl = String.format("Observation?patient=%s&code=20008", cdsHooksRequest.getContextProperty("patientId"));
            cdsHooksRequest.setPrefetch(getPrefetchElement(searchUrl, cdsHooksRequest.getFhirServer()), "MicroalbCrLabs");

            searchUrl = String.format("Observation?patient=%s&code=20009", cdsHooksRequest.getContextProperty("patientId"));
            cdsHooksRequest.setPrefetch(getPrefetchElement(searchUrl, cdsHooksRequest.getFhirServer()), "FootExams");

            searchUrl = String.format("Observation?patient=%s&code=20010", cdsHooksRequest.getContextProperty("patientId"));
            cdsHooksRequest.setPrefetch(getPrefetchElement(searchUrl, cdsHooksRequest.getFhirServer()), "EyeExams");

            searchUrl = String.format("MedicationStatement?patient=%s&code=999996", cdsHooksRequest.getContextProperty("patientId"));
            cdsHooksRequest.setPrefetch(getPrefetchElement(searchUrl, cdsHooksRequest.getFhirServer()), "ACEorARBMedications");
        }
    }

    // This is especially inefficient as the search must be done for each Request resource (and then converted to stu3):
    // MedicationOrder -> MedicationRequest, DiagnosticOrder or DeviceUseRequest -> ProcedureRequest, SupplyRequest
    public void resolveOrderReviewPrefetch(CdsHooksRequest cdsHooksRequest) {

        // TODO - clean this up

        if (cdsHooksRequest.getPrefetch().size() == 0) {
            String searchUrl = String.format("MedicationOrder?patient=%s&encounter=%s",
                    cdsHooksRequest.getContextProperty("patientId"), cdsHooksRequest.getContextProperty("encounterId")
            );
            org.hl7.fhir.instance.model.Bundle postfetch = FhirContext.forDstu2Hl7Org()
                    .newRestfulGenericClient(cdsHooksRequest.getFhirServer())
                    .search()
                    .byUrl(searchUrl)
                    .returnBundle(org.hl7.fhir.instance.model.Bundle.class)
                    .execute();
            cdsHooksRequest.setPrefetch(postfetch, "medication");

            searchUrl = String.format("DiagnosticOrder?patient=%s&encounter=%s", cdsHooksRequest.getContextProperty("patientId"), cdsHooksRequest.getContextProperty("encounterId"));
            org.hl7.fhir.instance.model.Bundle diagnosticOrders = FhirContext.forDstu2()
                    .newRestfulGenericClient(cdsHooksRequest.getFhirServer())
                    .search()
                    .byUrl(searchUrl)
                    .returnBundle(org.hl7.fhir.instance.model.Bundle.class)
                    .execute();
            cdsHooksRequest.setPrefetch(diagnosticOrders, "diagnosticOrders");

            searchUrl = String.format("DeviceUseRequest?patient=%s&encounter=%s", cdsHooksRequest.getContextProperty("patientId"), cdsHooksRequest.getContextProperty("encounterId"));
            org.hl7.fhir.instance.model.Bundle deviceUseRequests = FhirContext.forDstu2()
                    .newRestfulGenericClient(cdsHooksRequest.getFhirServer())
                    .search()
                    .byUrl(searchUrl)
                    .returnBundle(org.hl7.fhir.instance.model.Bundle.class)
                    .execute();
            cdsHooksRequest.setPrefetch(deviceUseRequests, "deviceUseRequests");

            searchUrl = String.format("ProcedureRequest?patient=%s&encounter=%s", cdsHooksRequest.getContextProperty("patientId"), cdsHooksRequest.getContextProperty("encounterId"));
            org.hl7.fhir.instance.model.Bundle procedureRequests = FhirContext.forDstu2()
                    .newRestfulGenericClient(cdsHooksRequest.getFhirServer())
                    .search()
                    .byUrl(searchUrl)
                    .returnBundle(org.hl7.fhir.instance.model.Bundle.class)
                    .execute();
            cdsHooksRequest.setPrefetch(procedureRequests, "procedureRequests");

            searchUrl = String.format("SupplyRequest?patient=%s&encounter=%s", cdsHooksRequest.getContextProperty("patientId"), cdsHooksRequest.getContextProperty("encounterId"));
            org.hl7.fhir.instance.model.Bundle supplyRequests = FhirContext.forDstu2()
                    .newRestfulGenericClient(cdsHooksRequest.getFhirServer())
                    .search()
                    .byUrl(searchUrl)
                    .returnBundle(org.hl7.fhir.instance.model.Bundle.class)
                    .execute();
            cdsHooksRequest.setPrefetch(supplyRequests, "supplyRequests");
        }
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        if (!request.getRequestURL().toString().endsWith("cds-services")) {
            throw new ServletException("This servlet is not configured to handle GET requests.");
        }

        CdsHooksHelper.DisplayDiscovery(response);
    }

    private String toJsonResponse(List<CdsCard> cards) {
        JsonObject ret = new JsonObject();
        JsonArray cardArray = new JsonArray();

        for (CdsCard card : cards) {
            cardArray.add(card.toJson());
        }

        ret.add("cards", cardArray);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return  gson.toJson(ret);
    }
}
