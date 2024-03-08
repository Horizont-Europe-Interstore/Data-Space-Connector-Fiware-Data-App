package it.eng.idsa.dataapp.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ContentBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.protocol.HTTP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import de.fraunhofer.iais.eis.ContractAgreementMessage;
import de.fraunhofer.iais.eis.ContractRequestMessage;
import de.fraunhofer.iais.eis.Message;

@Component
public class MessageUtil {

	@Value("${application.dataLakeDirectory}")
	private Path dataLakeDirectory;

	private static final Logger logger = LoggerFactory.getLogger(MessageUtil.class);

	public String createResponsePayload(Message requestHeader) {
		if (requestHeader instanceof ContractRequestMessage) {
			return createContractAgreement(dataLakeDirectory);
		} else if (requestHeader instanceof ContractAgreementMessage) {
			return null;
		} else {
			return createResponsePayload();
		}
	}

	public String createResponsePayload(String requestHeader) {
		if (requestHeader.contains(ContractRequestMessage.class.getSimpleName())) {
			return createContractAgreement(dataLakeDirectory);
		} else if (requestHeader.contains(ContractAgreementMessage.class.getSimpleName())) {
			return null;
		} else {
			return createResponsePayload();
		}
	}

	private String createResponsePayload() {
		// Put check sum in the payload
		DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
		Date date = new Date();
		String formattedDate = dateFormat.format(date);

		Map<String, String> jsonObject = new HashMap<>();
		jsonObject.put("firstName", "John");
		jsonObject.put("lastName", "Doe");
		jsonObject.put("dateOfBirth", formattedDate);
		jsonObject.put("address", "591  Franklin Street, Pennsylvania");
		jsonObject.put("checksum", "ABC123 " + formattedDate);
		Gson gson = new GsonBuilder().create();
		return gson.toJson(jsonObject);
	}

	private String createContractAgreement(Path dataLakeDirectory) {
		String contractAgreement = null;
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(dataLakeDirectory.resolve("contract_agreement.json"));
			contractAgreement = IOUtils.toString(bytes, "UTF8");
		} catch (IOException e) {
			logger.error("Error while reading contract agreement file from dataLakeDirectory {}", e);
		}
		return contractAgreement;
	}

	public static MultiValueMap<String, String> REMOVE_IDS_MESSAGE_HEADERS(HttpHeaders headers) {
		MultiValueMap<String, String> newHeaders = new LinkedMultiValueMap<>();
		newHeaders.putAll(headers);
		for (Iterator<String> iterator = newHeaders.keySet().iterator(); iterator.hasNext();) {
			String key = iterator.next();
			// String.contains is case sensitive so this should have minimal margin of error
			if (key.contains("IDS-")) {
				iterator.remove();
			}
		}
		return newHeaders;
	}

	public HttpEntity createMultipartMessageForm(String header, String payload, ContentType payloadContentType) {
		MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create().setStrictMode();

		ContentType payloadCT = ContentType.TEXT_PLAIN;

		if (payloadContentType == null) {
			if (isValidJSON(payload)) {
				payloadCT = ContentType.APPLICATION_JSON;
			}
		} else {
			payloadCT = payloadContentType;
		}

		try {
			FormBodyPart bodyHeaderPart;
			ContentBody headerBody = new StringBody(header, ContentType.create("application/ld+json"));
			bodyHeaderPart = FormBodyPartBuilder.create("header", headerBody).build();
			bodyHeaderPart.addField(HTTP.CONTENT_LEN, "" + header.length());
			multipartEntityBuilder.addPart(bodyHeaderPart);

			FormBodyPart bodyPayloadPart = null;
			if (payload != null) {
				ContentBody payloadBody = new StringBody(payload, payloadCT);
				bodyPayloadPart = FormBodyPartBuilder.create("payload", payloadBody).build();
				bodyPayloadPart.addField(HTTP.CONTENT_LEN, "" + payload.length());
				multipartEntityBuilder.addPart(bodyPayloadPart);
			}

		} catch (Exception e) {
			logger.error("Error while creating response ", e);
		}
		return multipartEntityBuilder.build();
	}

	public boolean isValidJSON(String json) {
		try {
			JsonParser.parseString(json);
		} catch (JsonSyntaxException e) {
			return false;
		}
		return true;
	}

	public String getUUID(String uri) {
		if (uri == null) {
			return null;
		}
		Pattern uuidPattern = Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){4}[a-f0-9]{8}");
		Matcher matcher = uuidPattern.matcher(uri);

		List<String> matches = new ArrayList<>();
		while (matcher.find()) {
			matches.add(matcher.group(0));
		}
		return !matches.isEmpty() ? matches.get(matches.size() - 1) : null;
	}
}
