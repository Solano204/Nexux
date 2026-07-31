package com.nexus.identity.web.dto.response;

import java.util.List;


public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String tokenType,
        String userId,
        List<String> roles,
        // Best-effort Cognito "Option B" token pair — null whenever Cognito
        // isn't configured or the call failed. accessToken/refreshToken
        // above are always present regardless; local login never depends
        // on these succeeding. See CognitoUserMirror.loginWithCognito().
        String cognitoAccessToken,
        String cognitoIdToken,
        String cognitoRefreshToken
) {}
