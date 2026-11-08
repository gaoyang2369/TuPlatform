import { readFile, writeFile } from 'node:fs/promises';
const scenes = JSON.parse(await readFile(new URL('./scenes.json', import.meta.url), 'utf8'));
const navigation = JSON.parse(await readFile(new URL('./navigation.json', import.meta.url), 'utf8'));
const safeJSON = value => JSON.stringify(value).replaceAll('<', '\\u003c');
const safeData = safeJSON(scenes);
const template = await readFile(new URL('./preview-template.html', import.meta.url), 'utf8');
await writeFile(new URL('../interaction-preview.html', import.meta.url), template.replace('/*__SCENES__*/[]', safeData).replace('/*__NAVIGATION__*/{}', safeJSON(navigation)), 'utf8');
console.log(`Built a self-contained design preview with ${scenes.length} scenes.`);
