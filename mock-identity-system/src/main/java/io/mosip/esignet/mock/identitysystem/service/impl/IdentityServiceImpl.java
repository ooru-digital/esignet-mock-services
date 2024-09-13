package io.mosip.esignet.mock.identitysystem.service.impl;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
		//TODO: This config need to taken from esignet.properties
		String apiUrl = identityDetailsUrl + individualId;
		ResponseEntity<String> response = restTemplate.getForEntity(apiUrl, String.class);
        String identityJson = response.getBody();
		//System.out.println("Response from API: " + identityJson);

		//Optional<MockIdentity> mockIdentity = identityRepository.findById(individualId);
		//System.out.println("mockIdentity.get().getIdentityJson()>>>>>>>>>>"+mockIdentity.get().getIdentityJson());
		if (identityJson.isEmpty()) {
			throw new MockIdentityException(ErrorConstants.INVALID_INDIVIDUAL_ID);
		}
		IdentityData identityData = new IdentityData();
		try {
			identityData = objectmapper.readValue(identityJson, IdentityData.class);
			//System.out.println("identityData>>>>>>>>>>"+identityData);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
			throw new MockIdentityException(ErrorConstants.JSON_PROCESSING_ERROR);
		}
		return identityData;
	}

}
