// Patch cat-translator-v2.html with Schötz 2024 calibrated values
const fs = require('fs');

let html = fs.readFileSync('cat-translator-v2.html', 'utf8');

// 1. Replace EMOTIONS array with Schötz 2024 calibrated parameters
//    Field names matched to what classify() expects: harm, rep, contour, purr
const newEmotions = `// === Schötz 2024 calibrated emotion model (50 cats, 969 meows) ===
// F0 data: Attention 559Hz(+/-40) n=487, Content 495Hz(+/-40) n=52,
//   Stressed 579Hz(+/-39) n=78, Food 581Hz(+/-38) n=341,
//   Greeting 484Hz(+/-44) n=61, Door 661Hz(+/-30) n=75
// Duration: Content 545ms, Food 728ms, Stressed 912ms, Carrier 932ms
// Intonation: Greeting=steep rise, Carrier=fall, Door=level
// Purr: Schötz 2015 F0=18-31Hz; Hiss: Yeon 2011 aperiodic
var EMOTIONS = [
  {id:'content',emoji:'😌',name:'满足/放松',desc:'咕噜声，舒服放松',
   color:'#7ec8a0',f0Lo:15,f0Hi:60,dLo:1.5,dHi:25,zLo:0.001,zHi:0.05,cLo:20,cHi:200,
   contour:'flat',harm:true,rep:false,e:'very-low',purr:true},
  {id:'attention',emoji:'🐱',name:'寻求关注',desc:'打招呼或想引起注意',
   color:'#f4a460',f0Lo:390,f0Hi:720,dLo:0.3,dHi:1.5,zLo:0.05,zHi:0.22,cLo:300,cHi:1100,
   contour:'rising',harm:true,rep:false,e:'medium',purr:false},
  {id:'hungry',emoji:'🍼',name:'饥饿/乞食',desc:'想吃东西了',
   color:'#ff8c52',f0Lo:490,f0Hi:700,dLo:0.3,dHi:1.2,zLo:0.07,zHi:0.25,cLo:380,cHi:1200,
   contour:'rising',harm:true,rep:true,e:'medium-high',purr:false},
  {id:'pain',emoji:'😿',name:'痛苦/不适',desc:'不舒服或紧张',
   color:'#ff6b6b',f0Lo:440,f0Hi:700,dLo:0.6,dHi:4,zLo:0.03,zHi:0.18,cLo:250,cHi:950,
   contour:'falling',harm:true,rep:false,e:'high',purr:false},
  {id:'angry',emoji:'😾',name:'愤怒/威胁',desc:'嘶嘶声，在警告',
   color:'#ff4757',f0Lo:1200,f0Hi:8000,dLo:0.1,dHi:4,zLo:0.30,zHi:0.70,cLo:1800,cHi:6000,
   contour:'flat',harm:false,rep:false,e:'medium',purr:false},
  {id:'mating',emoji:'🚨',name:'发情/领地',desc:'发情期嚎叫或宣示领地',
   color:'#b39dff',f0Lo:250,f0Hi:750,dLo:1.2,dHi:8,zLo:0.02,zHi:0.15,cLo:200,cHi:900,
   contour:'flat',harm:true,rep:true,e:'high',purr:false}
];`;

// Find and replace the EMOTIONS array (from 'var EMOTIONS = [' to the matching '];')
const emoStart = html.indexOf('var EMOTIONS = [');
if (emoStart < 0) { console.error('EMOTIONS array not found'); process.exit(1); }

// Find matching ]; for the array
let braceCount = 0;
let emoEnd = emoStart;
let started = false;
for (let i = emoStart; i < html.length; i++) {
    const ch = html[i];
    if (ch === '[' && !started) { started = true; braceCount = 1; }
    else if (ch === '[' && started) braceCount++;
    else if (ch === ']' && started) {
        braceCount--;
        if (braceCount === 0) { emoEnd = i + 1; break; }
    }
}
html = html.slice(0, emoStart) + newEmotions + html.slice(emoEnd);
console.log('EMOTIONS replaced (Schötz 2024 calibrated)');

// 2. Replace detectHumanVoice with faster, more accurate version
const huStart = html.indexOf('function detectHumanVoice(');
if (huStart < 0) { console.error('detectHumanVoice not found'); process.exit(1); }

// Find its closing }
let huBraceCount = 0;
let huEnd = huStart;
for (let i = huStart; i < html.length; i++) {
    if (html[i] === '{') huBraceCount++;
    else if (html[i] === '}') {
        huBraceCount--;
        if (huBraceCount === 0) { huEnd = i; break; }
    }
}

const newHumanDetect = `function detectHumanVoice(audioBuf, features, dur){
  // Human F0: male 85-180Hz, female 165-550Hz
  // Cat meow F0: 300-1200Hz (Schötz 2024), purr: 18-31Hz
  var s=audioBuf.getChannelData(0), n=s.length, sr=audioBuf.sampleRate;
  var score=0, ind=[], det={};
  det.peakHz=Math.round(features.peakFreq);
  det.zcrPct=Math.round(features.zcr*100);
  var f0h=findHumanF0(s,sr);
  det.f0Human=Math.round(f0h);

  // 1. Human F0 range (weight 4): male 85-180Hz, female 165-550Hz
  if(f0h>=70 && f0h<=500 && features.peakFreq>=70 && features.peakFreq<=550){
    score+=4;
    if(f0h<250) ind.push('基频'+det.f0Human+'Hz 在男性人声范围(85-250Hz)');
    else if(f0h<400) ind.push('基频'+det.f0Human+'Hz 在女性人声范围(165-400Hz)');
    else ind.push('基频'+det.f0Human+'Hz 在人声高声区');
  } else if(f0h>=500 && f0h<=800){
    score+=2;
    ind.push('基频'+det.f0Human+'Hz 接近人声上限(模仿猫叫)');
  }

  // 2. Purr exclusion: F0<60Hz + low ZCR + long (weight -4)
  if(features.peakFreq<60 && features.lowRatio>0.4 && dur>1.5){
    score-=4; ind.push('排除: 超低频长持续=猫咕噜声(F0='+det.peakHz+'Hz)');
  }

  // 3. Hiss exclusion: high ZCR + high peak (weight -4)
  if(features.zcr>0.30 && features.peakFreq>1500){
    score-=4; ind.push('排除: 嘶嘶声特征(ZCR='+det.zcrPct+'%)');
  }

  // 4. ZCR for human speech modulation
  if(features.zcr>0.04 && features.zcr<0.18 && f0h>80 && f0h<450){
    score+=1; ind.push('ZCR在人声调制范围内('+det.zcrPct+'%)');
  }

  // 5. Harmonic structure
  if(features.zcr<0.18 && f0h>70){
    score+=1; ind.push('谐波结构规则，人声特征');
  }

  // 6. Short duration
  if(dur<2.5 && f0h>70 && f0h<500) score+=0.5;

  var isHuman=(score>=4 && features.peakFreq>60 && features.peakFreq<600);
  return{isHuman:isHuman,confidence:Math.min(95,Math.max(5,Math.round((score+3)/10*100))),
    score:score,indicators:ind.slice(0,4),details:det};
}

function findHumanF0(samples,sr){
  var n=samples.length;
  var minLag=Math.max(2,Math.floor(sr/550));
  var maxLag=Math.min(n-1,Math.floor(sr/70));
  if(maxLag<=minLag) return 0;
  var step=Math.max(1,Math.floor(n/3000));
  var ds=[]; for(var i=0;i<n;i+=step) ds.push(samples[i]);
  var dsN=ds.length, dsMin=Math.max(1,Math.floor(minLag/step)), dsMax=Math.min(dsN-1,Math.floor(maxLag/step));
  var bestL=dsMin, bestC=-Infinity;
  for(var lag=dsMin;lag<=dsMax;lag++){
    var c=0; for(var i=0;i<dsN-lag;i++) c+=ds[i]*ds[i+lag];
    var norm=0; for(i=0;i<dsN;i++) norm+=ds[i]*ds[i];
    if(norm>0) c=c/(norm/dsN);
    if(c>bestC){bestC=c;bestL=lag;}
  }
  if(bestC<0.02) return 0;
  return Math.round(sr/(bestL*step));
}`;

html = html.slice(0, huStart) + newHumanDetect + html.slice(huEnd + 1);
console.log('detectHumanVoice replaced (faster F0 detection)');

// 3. Remove old helper functions (detectF0, countHarmonics, detectFormantPeaks, estimateSpectralSlope, countHarmonicPeaks)
for (const func of ['detectF0','countHarmonics','detectFormantPeaks','estimateSpectralSlope','countHarmonicPeaks']) {
    const idx = html.indexOf('function ' + func + '(');
    if (idx >= 0) {
        let bc = 0, end = idx;
        for (let i = idx; i < html.length; i++) {
            if (html[i] === '{') bc++;
            else if (html[i] === '}') { bc--; if (bc === 0) { end = i; break; } }
        }
        html = html.slice(0, idx) + html.slice(end + 1);
        console.log('Removed: ' + func);
    }
}

fs.writeFileSync('cat-translator-v2.html', html);
console.log('\nDone. cat-translator-v2.html updated with Schötz 2024 calibrated model.');
