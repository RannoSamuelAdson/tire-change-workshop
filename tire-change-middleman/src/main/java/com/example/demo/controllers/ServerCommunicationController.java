package com.example.demo.controllers;

import com.example.demo.models.TireChangeBookingRequest;
import com.example.demo.models.TireReplacementTimeSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
public class ServerCommunicationController {
    @Autowired
    private Environment env;

    public ServerCommunicationController(Environment env) {
        this.env = env;
    }

    public List<TireReplacementTimeSlot> routeGetRequestSending(String urlXML, String urlJSON, String workshopName, String endTime) {
        List<TireReplacementTimeSlot> timeSlots = new ArrayList<>();
        if (Objects.equals(env.getProperty("servers.responseBodyFormat." + workshopName), "XML")) {// If the format is XML
            timeSlots = sendGetRequestXML(workshopName, urlXML);
        }

        if (Objects.equals(env.getProperty("servers.responseBodyFormat." + workshopName), "JSON")) {// If the format is JSON
            timeSlots = sendGetRequestJSON(workshopName, urlJSON, endTime);
        }
        return timeSlots;
    }

    public ResponseEntity<String> sendUpdateRequest(String workshopName, String id) {
        // Construct the URL based on the properties
        String serverPort = env.getProperty("servers.port." + workshopName);
        String serverHost = env.getProperty("servers.host." + workshopName);
        String serverBookAddress = env.getProperty("servers.address.book." + workshopName);
        String url = serverPort + serverHost + id + "/" + serverBookAddress;
        String bookMethod = env.getProperty("servers.bookingMethod." + workshopName);
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> bookingResponse = new ResponseEntity<>(HttpStatus.OK);

        // Prepare the request body
        TireChangeBookingRequest bookingRequestBody = new TireChangeBookingRequest(env.getProperty("servers.contactInformation"));
        HttpHeaders headers = new HttpHeaders();
        HttpHeaders updatedHeaders = new HttpHeaders();

        if (Objects.equals(bookMethod, "PUT")) {
            headers.setContentType(MediaType.APPLICATION_XML);
            HttpEntity<TireChangeBookingRequest> requestEntity = new HttpEntity<>(bookingRequestBody, headers);
            bookingResponse = restTemplate.exchange(url, HttpMethod.PUT, requestEntity, String.class);
            updatedHeaders.putAll(bookingResponse.getHeaders());
            updatedHeaders.add("X-Put-Method-Executed", "true"); // Adding a flag for tests to check.
        }
        if (Objects.equals(bookMethod, "POST")) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<TireChangeBookingRequest> requestEntity = new HttpEntity<>(bookingRequestBody, headers);
            bookingResponse = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
            updatedHeaders.putAll(bookingResponse.getHeaders());
            updatedHeaders.add("X-Post-Method-Executed", "true"); // Adding a flag for tests to check.
        }

        return new ResponseEntity<>(bookingResponse.getBody(), updatedHeaders, bookingResponse.getStatusCode());
    }

    public List<TireReplacementTimeSlot> sendGetRequestXML(String workshopName, String urlString) {
        List<TireReplacementTimeSlot> timeSlotsList = new ArrayList<>();
        try {
            URL getURL = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) getURL.openConnection();
            connection.setRequestMethod("GET");
            try (InputStream inputStream = connection.getInputStream()) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(inputStream);

                NodeList timeslotNodes = document.getElementsByTagName("availableTime");
                for (int i = 0; i < timeslotNodes.getLength(); i++) {
                    Element timeslotElement = (Element) timeslotNodes.item(i);
                    timeSlotsList.add(new TireReplacementTimeSlot(
                            workshopName,
                            env.getProperty("servers.physicalAddress." + workshopName),
                            tryGetTextContent(timeslotElement, "uuid"),
                            tryGetTextContent(timeslotElement, "time"),
                            Integer.parseInt(Objects.requireNonNull(env.getProperty("servers.localTimezoneOffset." + workshopName))),
                            env.getProperty("servers.allowedVehicles." + workshopName)));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return timeSlotsList;
    }

    public List<TireReplacementTimeSlot> sendGetRequestJSON(String workshopName, String url, String endTime) {
        List<TireReplacementTimeSlot> timeSlots = new ArrayList<>();
        RestTemplate restTemplate = new RestTemplate();
        String jsonData = restTemplate.getForObject(url, String.class);

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(jsonData);

            for (JsonNode node : rootNode) {
                boolean available = node.get("available").asBoolean();
                String id = node.get("id").asText();
                String time = node.get("time").asText();

                if (HTTPFrontendRequestController.isDateBeforeOrEqualDateTime(endTime, time)) // Stops the reading when endTime is reached.
                    break;
                if (available) {
                    TireReplacementTimeSlot timeSlot = new TireReplacementTimeSlot(
                            workshopName,
                            env.getProperty("servers.physicalAddress." + workshopName),
                            id,
                            time,
                            Integer.parseInt(Objects.requireNonNull(env.getProperty("servers.localTimezoneOffset." + workshopName))),
                            env.getProperty("servers.allowedVehicles." + workshopName)
                    );
                    timeSlots.add(timeSlot);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return timeSlots;
    }

    static String tryGetTextContent(Element element, String tagName) {
        Node node = element.getElementsByTagName(tagName).item(0);
        return (node != null) ? node.getTextContent() : null;
    }
}
