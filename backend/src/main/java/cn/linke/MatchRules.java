package cn.linke;

final class MatchRules {
    private MatchRules() {}
    static boolean active(String state) { return "WAITING".equals(state)||"MUTUAL".equals(state); }
    static void response(String state, boolean initiator, String action) {
        if (!"WAITING".equals(state)) throw new ApiError(409,"MATCH_STATE_CHANGED","本次意向已变化，请读取最新进度后核对");
        if (!java.util.Set.of("ACCEPT","REJECT","WITHDRAW").contains(action)) throw ApiError.invalid("action","请选择有效操作");
        if (initiator != "WITHDRAW".equals(action)) throw new ApiError(403,"ACTION_NOT_ALLOWED","仅待回应方可接受或拒绝，发起方只能撤回待回应意向");
    }
}
