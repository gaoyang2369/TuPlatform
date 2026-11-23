import type { ResumeFields, Teacher, TeachingCase } from "./account-api";

export const styles = ["耐心细致", "擅长基础巩固", "习惯培养", "善于引导思考", "备课认真"];
export const trialPolicies = ["支持线上免费沟通15分钟", "支持首次试课半价", "按正常时薪试课", "暂不支持试课"];
export const emptyCase = (): TeachingCase => ({ student_grade: "", focus: "", outcome: "" });

export function resumeDraft(teacher?: Teacher | null) {
  return {
    schoolName: teacher?.schoolName ?? "", collegeMajor: teacher?.collegeMajor ?? "",
    gradeLevel: teacher?.gradeLevel ?? "", yearsExp: teacher?.yearsExp ?? "",
    gradesText: teacher?.teachGrades?.join("、") ?? "", subjectsText: teacher?.teachSubjects?.join("、") ?? "",
    teachingStyleTags: [...(teacher?.teachingStyleTags ?? [])],
    casesJson: teacher?.casesJson?.length ? teacher.casesJson.map(item => ({ ...item })) : [emptyCase()],
    trialPolicy: teacher?.trialPolicy ?? "暂不支持试课",
  };
}
export type ResumeDraft = ReturnType<typeof resumeDraft>;

export function resumeFields(draft: ResumeDraft): ResumeFields {
  const { gradesText, subjectsText, ...fields } = draft;
  const labels = (value: string) => value.split(/[、，,]/).map(item => item.trim()).filter(Boolean);
  return { ...fields, teachGrades: labels(gradesText), teachSubjects: labels(subjectsText) };
}
