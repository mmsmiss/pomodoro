var fs = require('fs');
var html = fs.readFileSync('cat-translator-v2.html', 'utf8');

// 1. Replace showResults to add feature diagnostics
var showStart = html.indexOf('function showResults(results,humanCheck){');
var oldShowEnd = html.indexOf('  // Animate\n  setTimeout', showStart);
oldShowEnd = html.indexOf('\n  // Animate\n', showStart);
if (oldShowEnd < 0) {
    console.error('showResults animate line not found');
    process.exit(1);
}

// Find the end of the function
var braceCount = 0;
var showFuncEnd = showStart;
for (var i = showStart; i < html.length; i++) {
    if (html[i] === '{') braceCount++;
    else if (html[i] === '}') { braceCount--; if (braceCount === 0) { showFuncEnd = i; break; } }
}

// 2. Replace classify function
var classStart = html.indexOf('function classify(f,dur){');
if (classStart < 0) {
    // Try finding the current version
    classStart = html.indexOf('// ── 情绪分类');
    if (classStart >= 0) classStart = html.indexOf('function classify', classStart);
}
console.log('classify at', classStart);

// Find end of classify
var classBraceStart = html.indexOf('{', classStart);
var classCount = 0;
var classEnd = classStart;
for (var i = classBraceStart; i < html.length; i++) {
    if (html[i] === '{') classCount++;
    else if (html[i] === '}') { classCount--; if (classCount === 0) { classEnd = i; break; } }
}
console.log('classify ends at', classEnd, 'length was', classEnd - classStart);

var newClassify = `function classify(f,dur){
  // EXPONENTIAL scoring — amplifies differences between emotions
  // Key insight: pow(0.5, dist) decays faster than linear, discrimination is better
  var results = EMOTIONS.map(function(e){
    var sc=0,w=0;

    // 1. F0 match (weight 4.0)
    var fmid=(e.f0Lo+e.f0Hi)/2, fh=(e.f0Hi-e.f0Lo)/2;
    var fdist=Math.abs(f.peakFreq-fmid)/Math.max(fh,1);
    sc+=4.0*Math.pow(0.45,fdist); w+=4.0;

    // 2. Duration (weight 3.5 — strong differentiator per Schötz 2024)
    var dmid=(e.dLo+e.dHi)/2, dh=(e.dHi-e.dLo)/2+0.01;
    var ddist=Math.abs(dur-dmid)/dh;
    sc+=3.5*Math.pow(0.35,ddist); w+=3.5;

    // 3. ZCR (weight 4.0 — strongest harmonic vs noise discriminator)
    var zmid=(e.zLo+e.zHi)/2, zh=(e.zHi-e.zLo)/2+0.001;
    var zdist=Math.abs(f.zcr-zmid)/zh;
    sc+=4.0*Math.pow(0.30,zdist); w+=4.0;

    // 4. Centroid (weight 1.5)
    var cmid=(e.cLo+e.cHi)/2, ch=(e.cHi-e.cLo)/2;
    var cdist=Math.abs(f.centroid-cmid)/Math.max(ch,1);
    sc+=1.5*Math.pow(0.5,cdist); w+=1.5;

    // 5. Energy (weight 1.5)
    var ev={'very-low':[0,0.03],'low':[0.01,0.06],'medium':[0.03,0.15],
            'medium-high':[0.08,0.3],'high':[0.15,0.6]}[e.e]||[0.02,0.2];
    var emid=(ev[0]+ev[1])/2, eh=(ev[1]-ev[0])/2+0.01;
    var edist=Math.abs(f.rms-emid)/eh;
    sc+=1.5*Math.pow(0.5,edist); w+=1.5;

    // 6. Harmonic consistency (weight 4.0 — strong discriminator)
    if(e.harm){
      if(f.zcr<0.16) sc+=4.0;
      else if(f.zcr<0.26) sc+=2.0;
      else if(f.zcr<0.35) sc+=0.5;
      else sc-=2.5;
    } else {
      if(f.zcr>0.38) sc+=4.0;
      else if(f.zcr>0.28) sc+=2.0;
      else sc-=3.5;
    }
    w+=4.0;

    // 7. Intonation contour (weight 5.0 — Schötz 2024 KEY FINDING)
    if(e.contour==='rising' && f.contour>0.07) sc+=5.0;
    else if(e.contour==='rising' && f.contour>0.03) sc+=2.5;
    else if(e.contour==='falling' && f.contour<-0.07) sc+=5.0;
    else if(e.contour==='falling' && f.contour<-0.03) sc+=2.5;
    else if(e.contour==='flat' && Math.abs(f.contour)<=0.03) sc+=5.0;
    else if(Math.abs(f.contour)<=0.06) sc+=2.0;
    else sc-=2.0;
    w+=5.0;

    // 8. Purr-specific (weight 3.5)
    if(e.purr){
      sc+=3.5*f.lowRatio;
      w+=3.5;
    }

    // 9. Repetition (weight 2.0)
    if(e.rep && dur<0.7) sc+=2.0;
    w+=2.0;

    var rawScore=w>0?Math.max(0,sc/w):0;
    return{eid:e.id,emoji:e.emoji,name:e.name,desc:e.desc,color:e.color,rawScore:rawScore};
  });

  results.sort(function(a,b){return b.rawScore-a.rawScore;});

  // SOFMAX to amplify score differences
  var top3=results.slice(0,3);
  var totalExp=0;
  top3.forEach(function(r){totalExp+=Math.exp(r.rawScore*4)});
  if(totalExp<0.001) totalExp=3;

  // Add diagnostics to the result
  var classified=top3.map(function(r){
    return{eid:r.eid,emoji:r.emoji,name:r.name,desc:r.desc,color:r.color,
      probability:Math.min(93,Math.max(2,Math.round(Math.exp(r.rawScore*4)/totalExp*100)))};
  });

  // Store diagnostic info on lastResult for display
  classified._diag={peakHz:Math.round(f.peakFreq),durMs:Math.round(dur*1000),
    zcrPct:Math.round(f.zcr*100),contourSign:Math.round(f.contour*100)};
  return classified;
}`;

html = html.slice(0, classStart) + newClassify + html.slice(classEnd + 1);
console.log('classify replaced');

// 3. Update analyze() to store and display diagnostics
var analyzeStart = html.indexOf('async function analyze(){');
var analyzeBrace = html.indexOf('{', analyzeStart);
var analyzeCount = 0;
var analyzeEnd = analyzeStart;
for (var i = analyzeBrace; i < html.length; i++) {
    if (html[i] === '{') analyzeCount++;
    else if (html[i] === '}') { analyzeCount--; if (analyzeCount === 0) { analyzeEnd = i; break; } }
}

// Find the lastResult line in analyze
var lastResultLine = html.indexOf('lastResult={features', analyzeStart);
var showResultsLine = html.indexOf('showResults(results,humanCheck)', analyzeStart);
if (showResultsLine > 0) {
    var before = html.slice(0, showResultsLine);
    var after = html.slice(showResultsLine);
    // Replace the showResults call to pass diagnostics
    after = after.replace('showResults(results,humanCheck)', 'showResults(results,humanCheck,f)');
    html = before + after;
    console.log('showResults updated to include features');
}

// 4. Update showResults to display diagnostic data
if (html.indexOf('function showResults(results,humanCheck){') >= 0) {
    // Add features parameter
    html = html.replace('function showResults(results,humanCheck){', 'function showResults(results,humanCheck,rawFeatures){');

    // After the result items, add diagnostic info
    var animateMarker = html.indexOf('  // Animate');
    if (animateMarker < 0) animateMarker = html.indexOf('  // Animate\n  setTimeout', showStart);
    if (animateMarker < 0) animateMarker = html.indexOf('  setTimeout(function(){\n    var bars', showStart);

    if (animateMarker > 0) {
        var diagInsert = '  // Show diagnostic data\n'+
          '  if(rawFeatures){\n'+
          '    area.innerHTML+=\n'+
          '      \'<div style="margin-top:6px;font-size:10px;color:#6a6a88;text-align:center">\'+\n'+
          '        \'🔬 峰值:\'+Math.round(rawFeatures.peakFreq)+\'Hz · 时长:\'+Math.round(dur||0)+\'ms · \'+\n'+
          '        \'ZCR:\'+Math.round(rawFeatures.zcr*100)+\'% · 语调:\'+(rawFeatures.contour>0.04?\''+"'"+'↗\'+(Math.round(rawFeatures.contour*100)):rawFeatures.contour<-0.04?\''+"'"+'↘\'+(-Math.round(rawFeatures.contour*100)):\''+"'"+'→\')+\'%\'+\n'+
          '      \'</div>\';\n'+
          '    dur=rawFeatures.n/rawFeatures.sr*1000;\n'+
          '  }\n'+
          '  \n';
        html = html.slice(0, animateMarker) + diagInsert + html.slice(animateMarker);
        console.log('diagnostic info added to showResults');
    }
}

fs.writeFileSync('cat-translator-v2.html', html);
console.log('All patches applied successfully');
