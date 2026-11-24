import qrcode from "qrcode-generator";
import { moneyText } from "./discovery-display";
import type { PublicTeacher } from "./match-types";
import { areaNames, categoryNames } from "./request-display";
import { teachingIntroduction } from "./teacher-introduction";

const width = 750, scale = 2;
const ink = "#0f172a", muted = "#475569", blue = "#2563eb";
const font = '"PingFang SC","Microsoft YaHei",system-ui,sans-serif';

async function avatarImage(src?: string | null): Promise<HTMLImageElement | null> {
  if (!src) return null;
  const url = new URL(src, location.origin);
  if (!["http:", "https:"].includes(url.protocol)) return null;
  return new Promise(resolve => {
    const image = new Image();
    const finish = (result: HTMLImageElement | null) => {
      clearTimeout(timer);
      image.onload = image.onerror = null;
      resolve(result);
    };
    const timer = setTimeout(() => finish(null), 6000);
    image.crossOrigin = "anonymous";
    image.onload = () => finish(image.naturalWidth && image.naturalHeight ? image : null);
    image.onerror = () => finish(null);
    image.src = url.href;
  });
}

function highlights(values: string[] = []) {
  let line = "";
  for (const value of values) {
    const next = line ? line + "、" + value : value;
    if (line && Array.from(next).length > 100) return line + " 等" + values.length + "项";
    line = next;
  }
  return line;
}

/** The same layout pass measures and paints, so wrapped text pushes every following section down. */
export async function renderTeacherPoster(teacher: PublicTeacher, url: string): Promise<string> {
  const image = await avatarImage(teacher.avatar_url);
  const qr = qrcode(0, "M");
  qr.addData(url); qr.make();
  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("当前浏览器无法生成图片，请换个浏览器重试。");
  const c = ctx;
  function face(size: number, weight: number) { c.font = weight + " " + size + "px " + font; }
  function wrapped(text: string, max: number) {
    const lines: string[] = [];
    let line = "";
    for (const char of text) {
      if (line && c.measureText(line + char).width > max) { lines.push(line); line = ""; }
      line += char;
    }
    if (line) lines.push(line);
    return lines;
  }
  function box(x: number, y: number, w: number, h: number, radius: number, color: string) {
    // arcTo also works in older WeChat WebViews without roundRect.
    c.beginPath(); c.moveTo(x+radius,y); c.arcTo(x+w,y,x+w,y+h,radius);
    c.arcTo(x+w,y+h,x,y+h,radius); c.arcTo(x,y+h,x,y,radius); c.arcTo(x,y,x+w,y,radius);
    c.closePath(); c.fillStyle=color; c.fill();
  }
  function text(value: string, x: number, y: number, size: number, weight = 400, color = ink) {
    face(size, weight); c.fillStyle=color; c.fillText(value,x,y);
  }
  function paragraph(value: string, x: number, y: number, available: number, size: number, weight = 400, color = ink) {
    face(size, weight);
    for (const line of wrapped(value, available)) {
      text(line, x, y, size, weight, color);
      y += Math.ceil(size * 1.5);
    }
    return y;
  }
  const rate = teacher.priceMin == null || teacher.priceMax == null ? "面议" :
    "¥" + moneyText(teacher.priceMin) + (moneyText(teacher.priceMax) === moneyText(teacher.priceMin) ? "" : "–" + moneyText(teacher.priceMax)) + " / 小时";
  const bio = teachingIntroduction(teacher.introduction);
  const intro = (bio.direction || bio.paragraphs[0]?.text || "").match(/^[^。！？\n]+[。！？]?/u)?.[0] || "";
  const facts: [string,string][] = [
    ["擅长年级", highlights(teacher.teachGrades) || bio.grade],
    ["教授科目", highlights(teacher.teachSubjects) || bio.subject || teacher.categoryCodes.map(code=>categoryNames[code] || code).join("、")],
    ["服务街道", teacher.areaCodes.map(code=>(areaNames[code] || code)+"街道").join("、") ||
      (teacher.serviceModes.includes("ONLINE") ? "线上授课" : "可沟通")],
    ["首次试课", teacher.trialPolicy || "可沟通"],
  ];
  function layout() {
    c.textBaseline="top";
    const nameX=193;
    let y=paragraph(teacher.displayName,nameX,198,477,42,700);
    y=paragraph([teacher.collegeMajor,teacher.gradeLevel].filter(Boolean).join(" · "),nameX,y+2,477,23,400,muted);
    y=Math.max(310,y+8);
    const badges=[teacher.is_school_verified ? "本校学籍已核验" : "邮箱已验证"];
    if (teacher.is_skill_verified) badges.push("技能证书已核验");
    let x=76;
    for(const badge of badges) {
      face(21,600); const w=Math.ceil(c.measureText(badge).width)+32;
      box(x,y,w,38,16,"#e9f1ff"); text(badge,x+16,y+8,21,600,blue); x+=w+10;
    }
    y+=58; face(32,700);
    const rateWidth=c.measureText(rate).width;
    if(rateWidth<=438) {
      box(76,y,598,70,14,"#f0f5ff"); text("参考时薪",96,y+22,22,500,muted);
      text(rate,654-rateWidth,y+15,32,700,blue);
      y+=101;
    } else {
      const h=58+wrapped(rate,558).length*48;
      box(76,y,598,h,14,"#f0f5ff");text("参考时薪",96,y+12,22,500,muted);
      paragraph(rate,96,y+46,558,32,700,blue);y+=h+31;
    }
    if(intro) y=paragraph(intro,76,y,598,26,500)+18;
    let tagX=76;
    for(const tag of teacher.teachingStyleTags || []) {
      face(20,500); const tagWidth=Math.ceil(c.measureText(tag).width)+28;
      if (tagX+tagWidth>674) {tagX=76;y+=44;}
      box(tagX,y,tagWidth,36,12,"#edf2f7");text(tag,tagX+14,y+7,20,500,muted);tagX+=tagWidth+9;
    }
    if(teacher.teachingStyleTags?.length) y+=60;
    for(const [label,value] of facts) {
      if(!value) continue;
      text(label,76,y+3,21,500,muted);
      y=paragraph(value,206,y,468,24,600)+12;
    }
    return Math.max(y+32,744);
  }
  const footerY=layout();
  const height=Math.max(1000,footerY+256);
  canvas.width=width*scale; canvas.height=height*scale; c.scale(scale,scale);
  c.fillStyle="#eef4fc"; c.fillRect(0,0,width,height);
  c.fillStyle=blue; c.fillRect(0,0,width,246);
  c.save(); c.strokeStyle="rgba(255,255,255,0.15)"; c.lineWidth=2;
  for(const r of [63,98,133]) { c.beginPath(); c.arc(679,8,r,0,Math.PI*2);c.stroke(); }
  c.restore(); c.textBaseline="top";
  text("邻课 · 深圳坪山",48,42,40,700,"#ffffff");
  text("本校在校生直连 · 零中介费",50,103,24,500,"#e9f1ff");
  box(42,160,666,footerY-160,26,"#ffffff");
  layout();
  c.save(); box(76,198,94,94,20,"#dce9ff"); c.clip();
  if(image) {
    const side=Math.min(image.naturalWidth,image.naturalHeight);
    c.drawImage(image,(image.naturalWidth-side)/2,(image.naturalHeight-side)/2,side,side,76,198,94,94);
  } else {
    face(44,700);c.fillStyle=blue;c.textAlign="center";c.textBaseline="middle";
    c.fillText(Array.from(teacher.displayName.trim())[0] || "师",123,245);
  }
  c.restore();

  const foot=height-233;
  box(42,foot,666,197,23,"#e2ecff");
  text("查看老师主页",76,foot+39,32,700);
  text("长按或微信扫码，",76,foot+94,23,400,muted);
  text("查看老师详情与可约时段",76,foot+128,23,400,muted);
  const count=qr.getModuleCount(), module=Math.floor(175*scale/(count+8))/scale;
  const qrSize=module*(count+8), qrX=Math.floor(578-qrSize/2), qrY=Math.floor(foot+(197-qrSize)/2);
  c.fillStyle="#fff";c.fillRect(qrX,qrY,qrSize,qrSize);c.fillStyle="#0f172a";
  for(let row=0;row<count;row++) for(let col=0;col<count;col++) {
    if(qr.isDark(row,col)) c.fillRect(qrX+(col+4)*module,qrY+(row+4)*module,module,module);
  }
  const png=canvas.toDataURL("image/png");
  if (!png.startsWith("data:image/png;")) throw new Error("图片生成失败，请重试。");
  return png;
}
