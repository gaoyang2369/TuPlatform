package cn.linke;

import java.util.List;

final class ResumeFixtures {
    static TeacherBody teacher(Long version, String name, List<String> categories, List<String> modes, List<String> areas,
                               String intro, String min, String max, String availability) {
        return new TeacherBody(version, name, categories, modes, areas, intro, min, max, availability,
            null, "设计学院", "大三", List.of("小学"), List.of("绘画"), "两年绘画辅导经验",
            List.of("耐心细致"), List.of(new TeachingCase("小学", "色彩练习", "能区分冷暖色")), "支持首次试课半价");
    }
    private ResumeFixtures() {}
}
