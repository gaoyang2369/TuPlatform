package cn.linke;

import java.util.List;
import java.util.Map;
import java.util.UUID;

record RegisterBody(String account, String password, String email, Boolean adultConfirmed) {}
record LoginBody(String account, String password) {}
record TokenBody(String token) {}
record VerifyBody(String token, String registrationPassword) {}
record ResendBody(String registrationFlowId) {}
record ForgotBody(String email) {}
record ResetBody(String token, String newPassword) {}
record IdentityBody(String identity) {}
record BaseProfileBody(Long expectedVersion, String displayName) {}
record VersionBody(Long expectedVersion) {}
record TeacherBody(Long expectedVersion, String displayName, List<String> categoryCodes,
    List<String> serviceModes, List<String> areaCodes, String introduction,
    String priceMin, String priceMax, String availabilityText) {}
record Me(UUID id, String account, String email, boolean emailVerified, String businessIdentity,
    boolean operatorGranted, long profileVersion) {}
record Account(UUID id, String account, String email, String passwordHash, boolean verified,
    String identity, boolean operator, UUID flowId, String contextHash, long profileVersion) {}
record Registration(UUID registrationFlowId, String context, boolean mailAccepted) {}
record LoginResult(Me me, String session) {}
record Profile(String displayName, long version, Map<String, Object> teacher) {}
