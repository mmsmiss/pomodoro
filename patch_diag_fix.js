var fs = require('fs');
var html = fs.readFileSync('cat-translator-v2.html', 'utf8');

// Fix the diagnostic display — replace the broken section
var oldMarker = '// Show diagnostic data';
var newMarker = '// Animate';

var diagStart = html.indexOf(oldMarker);
var animateIdx = html.indexOf(newMarker, diagStart);

if (diagStart < 0 || animateIdx < 0) {
    console.error('Could not find diagnostic section. diagStart=' + diagStart + ' animateIdx=' + animateIdx);
    process.exit(1);
}

var replacement = '  // Show diagnostic data\n'+
'  if(rawFeatures){\n'+
'    var diagDur=Math.round(rawFeatures.n/rawFeatures.sr*1000);\n'+
'    var contourSign=rawFeatures.contour>0.04?"up":rawFeatures.contour<-0.04?"dn":"flat";\n'+
'    area.innerHTML+=\n'+
'      \'<div style="margin-top:6px;font-size:10px;color:#6a6a88;text-align:center">\'+\n'+
'        \'🔬 峰值:\'+Math.round(rawFeatures.peakFreq)+\'Hz · 时长:\'+diagDur+\'ms · \'+\n'+
'        \'ZCR:\'+Math.round(rawFeatures.zcr*100)+\'% · 语调:\'+contourSign+Math.round(Math.abs(rawFeatures.contour)*100)+\'%\'+\n'+
'      \'</div>\';\n'+
'  }\n'+
'\n'+
'  // Animate\n';

html = html.slice(0, diagStart) + replacement + html.slice(animateIdx + newMarker.length + 1);

// Also fix the showResults signature if needed
html = html.replace('function showResults(results,humanCheck,rawFeatures){', 'function showResults(results,humanCheck,rawFeatures){');

fs.writeFileSync('cat-translator-v2.html', html);
console.log('Diagnostic display fixed');
