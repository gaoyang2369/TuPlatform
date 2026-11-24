export function teachingIntroduction(introduction: string) {
  const fields: Record<"direction" | "grade" | "subject" | "background" | "method" | "notes" | "prose", string[]> = {
    direction: [], grade: [], subject: [], background: [], method: [], notes: [], prose: [],
  };
  let section: keyof typeof fields = "prose";
  for (const raw of (introduction || "").split(/\r?\n/)) {
    const line = raw.trim();
    const match = line.match(/^(教学背景|教育背景|背景|教学经历|教学方式|教学方法|教学方向|服务说明|方向|擅长(?:领域|技能|科目)?|特长|技能|适教(?:范围|年级)?|年级(?:范围)?|科目|学科)\s*[：:]\s*(.*)$/);
    if (!match) {
      if (/^[^：:]{1,12}[：:]/.test(line)) section = "prose";
      if (line) fields[section].push(line);
      else section = "prose";
      continue;
    }
    const [, label, value] = match;
    if (/背景|经历/.test(label)) section = "background";
    else if (/教学方[式法]/.test(label)) section = "method";
    else if (label === "服务说明") section = "notes";
    else if (/^(?:适教|年级)/.test(label)) section = "grade";
    else if (label === "科目" || label === "学科") section = "subject";
    else section = "direction";
    if (value) fields[section].push(value);
  }
  return {
    direction: fields.direction.join(" · "),
    grade: fields.grade.join(" / "),
    subject: fields.subject.join(" / ").replace(/\s*、\s*/g, " / "),
    paragraphs: [
      { label: "教学背景与方式", text: fields.prose.join("\n") },
      { label: "教学背景", text: fields.background.join("\n") },
      { label: "教学方式", text: fields.method.join("\n") },
      { label: "服务说明", text: fields.notes.join("\n") },
    ].filter(part => part.text),
  };
}
