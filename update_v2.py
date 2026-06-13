import re

with open('cat-translator-v2.html', 'r', encoding='utf-8') as f:
    content = f.read()

def find_function_end(content, start_idx):
    brace_count = 0
    for i in range(start_idx, len(content)):
        c = content[i]
        if c == '{':
            brace_count += 1
        elif c == '}':
            brace_count -= 1
            if brace_count == 0:
                return i
    return -1

def remove_function(content, func_name):
    prefix = 'function ' + func_name + '('
    if prefix in content:
        start_idx = content.index(prefix)
        end_idx = find_function_end(content, start_idx)
        if end_idx >= 0:
            return content[:start_idx] + content[end_idx+1:]
    return content

# 1. Replace EMOTIONS array
old_start = 'var EMOTIONS = ['
start_idx = content.index(old_start)
end_idx = content.index('];', start_idx) + 2

new_emotions = """// === Schötz 2024 calibrated emotion model (see update_v2.py for paper references) ===
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
];

// === Reference: Schötz, van de Weijer & Eklund (2024) ===
// "Context effects on duration, fundamental frequency, and intonation
//  in human-directed domestic cat meows"
// Applied Animal Behaviour Science, 50 cats, 969 meows
// F0 data from Tables 3-4 (means +/- SD):
//   Greeting: 484Hz(±44), Door: 661Hz(±30), Food: 581Hz(±38)
//   Play: 393Hz(±36), Lifting: 654Hz(±39), Carrier: 546Hz(±33)
//   Content: 495Hz(±40), Attention: 559Hz(±40), Stressed: 579Hz(±39)
// Duration: Content 545ms, Play 561ms, Greeting 670ms,
//   Food 728ms, Lifting 724ms, Door 754ms, Stressed 912ms
// Intonation: Greeting=steepest rising, Carrier=falling, Door=level
// Purr: Schötz 2015 — F0 18-31Hz, ingressive 23-26Hz, egressive 21-27Hz
// Cry-purr: McComb et al. 2009 — 300-600Hz embedded in ~27Hz purr"""

content = content[:start_idx] + new_emotions + content[end_idx:]

# 2. Replace classify function
start_idx = content.index('function classify(f,dur){')
end_idx = find_function_end(content, start_idx)

new_classify = """function classify(f,dur){
  var results = EMOTIONS.map(function(e){
    var sc=0,w=0;

    // 1. F0 match (weight 4.0 — strongest signal, Schoetz 2024)
    var fmid=(e.f0Lo+e.f0Hi)/2, fr=(e.f0Hi-e.f0Lo)/2;
    var fd=Math.abs(f.peakFreq-fmid)/Math.max(fr,1);
    sc+=4.0*Math.max(0,1-fd); w+=4.0;

    // 2. Duration match (weight 2.5 — Table 3: 545-912ms range)
    var dmid=(e.dLo+e.dHi)/2, dr=(e.dHi-e.dLo)/2;
    var dd=Math.abs(dur-dmid)/Math.max(dr,0.01);
    sc+=2.5*Math.max(0,1-dd); w+=2.5;

    // 3. ZCR match (weight 3.0 — discriminates harmonic meow vs aperiodic hiss)
    var zmid=(e.zLo+e.zHi)/2, zr=(e.zHi-e.zLo)/2+0.001;
    var zd=Math.abs(f.zcr-zmid)/zr;
    sc+=3.0*Math.max(0,1-zd); w+=3.0;

    // 4. Centroid match (weight 1.5)
    var cmid=(e.cLo+e.cHi)/2, cr=(e.cHi-e.cLo)/2;
    var cd=Math.abs(f.centroid-cmid)/Math.max(cr,1);
    sc+=1.5*Math.max(0,1-cd); w+=1.5;

    // 5. Energy match (weight 1.5)
    var ev={'very-low':[0,0.03],'low':[0.01,0.06],'medium':[0.03,0.15],'medium-high':[0.08,0.3],'high':[0.15,0.6]}[e.e]||[0.02,0.2];
    var emid=(ev[0]+ev[1])/2, er=(ev[1]-ev[0])/2+0.01;
    var ed=Math.abs(f.rms-emid)/er;
    sc+=1.5*Math.max(0,1-ed); w+=1.5;

    // 6. Harmonic consistency (weight 2.0)
    if(e.harm){
      if(f.zcr<0.20) sc+=2.0;
      else if(f.zcr<0.30) sc+=1.0;
      else sc-=1.5;
    } else {
      if(f.zcr>0.30) sc+=2.0;
      else if(f.zcr>0.22) sc+=1.0;
      else sc-=2.0;
    }
    w+=2.0;

    // 7. Intonation contour (weight 2.0 — key Schoetz 2024 finding)
    if(e.contour==='rising' && f.contour>0.04) sc+=2.0;
    else if(e.contour==='falling' && f.contour<-0.04) sc+=2.0;
    else if(e.contour==='flat' && Math.abs(f.contour)<=0.04) sc+=2.0;
    else if(Math.abs(f.contour)<=0.06) sc+=1.0;
    w+=2.0;

    // 8. Purr-specific (weight 2.5)
    if(e.purr) sc+=2.5*f.loRatio;
    w+=2.5;

    // 9. Repetition bonus
    if(e.rep && dur<0.8) sc+=1.5;
    w+=1.5;

    return{eid:e.id,emoji:e.emoji,name:e.name,desc:e.desc,color:e.color,prob:Math.max(0,sc/Math.max(w,0.01))};
  });

  results.sort(function(a,b){return b.prob-a.prob;});
  var top3=results.slice(0,3);
  var total=top3.reduce(function(s,r){return s+Math.max(0.001,r.prob)},0);
  return top3.map(function(r){
    return{eid:r.eid,emoji:r.emoji,name:r.name,desc:r.desc,color:r.color,
      probability:Math.min(98,Math.max(2,Math.round(Math.max(0.001,r.prob)/total*100)))};
  });
}"""

content = content[:start_idx] + new_classify + content[end_idx+1:]

# 3. Replace detectHumanVoice (human detection)
start_idx = content.index('function detectHumanVoice(')
end_idx = find_function_end(content, start_idx)

new_human = """function detectHumanVoice(audioBuf, features, dur){
  var s=audioBuf.getChannelData(0), n=s.length, sr=audioBuf.sampleRate;
  var score=0, ind=[], det={};
  det.peakHz=Math.round(features.peakFreq);
  det.zcrPct=Math.round(features.zcr*100);
  var f0h=findHumanF0(s,sr);
  det.f0Human=Math.round(f0h);

  // 1. Human F0 range: male 85-180Hz, female 165-550Hz (weight 4)
  if(f0h>=70 && f0h<=500 && features.peakFreq>=70 && features.peakFreq<=550){
    score+=4;
    if(f0h<250) ind.push('基频'+det.f0Human+'Hz 在男性人声范围(85-250Hz)');
    else if(f0h<400) ind.push('基频'+det.f0Human+'Hz 在女性人声范围(165-400Hz)');
    else ind.push('基频'+det.f0Human+'Hz 在人声高声区');
  } else if(f0h>=500 && f0h<=800){
    score+=2;
    ind.push('基频'+det.f0Human+'Hz 接近人声上限(模仿猫叫)');
  }

  // 2. Purr exclusion (weight -4)
  if(features.peakFreq<60 && features.lowRatio>0.4 && dur>1.5){
    score-=4; ind.push('排除: 超低频长持续=猫咕噜声(F0='+det.peakHz+'Hz)');
  }

  // 3. Hiss exclusion (weight -4)
  if(features.zcr>0.30 && features.peakFreq>1500){
    score-=4; ind.push('排除: 嘶嘶声特征(ZCR='+det.zcrPct+'%)');
  }

  // 4. ZCR for human speech modulation (0.05-0.18)
  if(features.zcr>0.04 && features.zcr<0.18 && f0h>80 && f0h<450){
    score+=1; ind.push('ZCR在人声调制范围内('+det.zcrPct+'%)');
  }

  // 5. Harmonic structure
  if(features.zcr<0.18 && f0h>70){
    score+=1; ind.push('谐波结构规则，人声特征');
  }

  // 6. Duration
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
}"""

content = content[:start_idx] + new_human + content[end_idx+1:]

# 4. Remove old heavy functions
# Remove detectF0
for func in ['detectF0', 'countHarmonics', 'detectFormantPeaks', 'estimateSpectralSlope', 'countHarmonicPeaks']:
    content = remove_function(content, func)

with open('cat-translator-v2.html', 'w', encoding='utf-8') as f:
    f.write(content)

print('Update complete.')
print('EMOTIONS calibrated to Schötz 2024 (50 cats, 969 meows)')
print('classify() updated with 9-factor scoring')
print('detectHumanVoice() simplified with findHumanF0()')
print('Removed slow DFT-based helper functions')
