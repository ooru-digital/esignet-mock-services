package io.mosip.esignet.mock.identitysystem.service.impl;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.esignet.mock.identitysystem.dto.IdentityData;
import io.mosip.esignet.mock.identitysystem.entity.MockIdentity;
import io.mosip.esignet.mock.identitysystem.exception.MockIdentityException;
import io.mosip.esignet.mock.identitysystem.repository.IdentityRepository;
import io.mosip.esignet.mock.identitysystem.service.IdentityService;
import io.mosip.esignet.mock.identitysystem.util.ErrorConstants;

@Service
public class IdentityServiceImpl implements IdentityService {
	
	@Autowired
	ObjectMapper objectmapper;

	@Autowired
	IdentityRepository identityRepository;

	@Value("${mosip.esignet.authenticator.credissuer.identity-details-url}")
    private String identityDetailsUrl;

	@Value("${mosip.esignet.authenticator.credissuer.bearer-token}")
    private String credIssuerBeaerToken;

	@Override
	public void addIdentity(IdentityData identityData) throws MockIdentityException {
		if (identityRepository.findById(identityData.getIndividualId()).isPresent()) {
			throw new MockIdentityException(ErrorConstants.DUPLICATE_INDIVIDUAL_ID);
		}
		MockIdentity mockIdentity = new MockIdentity();
		try {
			mockIdentity.setIdentityJson(objectmapper.writeValueAsString(identityData));
		} catch (JsonProcessingException e) {
			throw new MockIdentityException(ErrorConstants.JSON_PROCESSING_ERROR);
		}
		mockIdentity.setIndividualId(identityData.getIndividualId());
		identityRepository.save(mockIdentity);
	}

	// @Override
	// public IdentityData getIdentity(String individualId) throws MockIdentityException {
	// 	Optional<MockIdentity> mockIdentity = identityRepository.findById(individualId);
	// 	if (!mockIdentity.isPresent()) {
	// 		throw new MockIdentityException(ErrorConstants.INVALID_INDIVIDUAL_ID);
	// 	}
	// 	IdentityData identityData = new IdentityData();
	// 	try {
	// 		identityData = objectmapper.readValue(mockIdentity.get().getIdentityJson(), IdentityData.class);
	// 	} catch (JsonProcessingException e) {
	// 		throw new MockIdentityException(ErrorConstants.JSON_PROCESSING_ERROR);
	// 	}
	// 	return identityData;
	// }

	@Override
	public IdentityData getIdentity(String individualId) throws MockIdentityException {
		//get the identity from credissuer
		RestTemplate restTemplate = new RestTemplate();
		String identityJson = null;
		//TODO: This config need to taken from esignet.properties
		String apiUrl = identityDetailsUrl + individualId;
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", "Bearer " + credIssuerBeaerToken);
		HttpEntity<String> entity = new HttpEntity<>(headers);
		try {
			ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class);
			identityJson = response.getBody();
		} catch (Exception e) {
			e.printStackTrace();
			throw new MockIdentityException(ErrorConstants.INVALID_INDIVIDUAL_ID);
		}
		//System.out.println("Response from API: " + identityJson);

		//Optional<MockIdentity> mockIdentity = identityRepository.findById(individualId);
		//System.out.println("mockIdentity.get().getIdentityJson()>>>>>>>>>>"+mockIdentity.get().getIdentityJson());
		if (identityJson.isEmpty()) {
			throw new MockIdentityException(ErrorConstants.INVALID_INDIVIDUAL_ID);
		}
		IdentityData identityData = new IdentityData();
		try {
			identityData = objectmapper.readValue(identityJson, IdentityData.class);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
			throw new MockIdentityException(ErrorConstants.JSON_PROCESSING_ERROR);
		}
		return identityData;
	}

}
