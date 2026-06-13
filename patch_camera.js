var fs = require('fs');
var html = fs.readFileSync('cat-translator-v2.html', 'utf8');

// 1. Add camera modal before the my-cat panel
var marker = html.indexOf('  <!-- ');  // finds "我的猫咪" comment
// Find the actual marker
var lines = html.split('\n');
var insertAfter = -1;
for (var i = 0; i < lines.length; i++) {
    if (lines[i].indexOf('我的猫咪') >= 0) {
        insertAfter = i - 1;  // insert before this line
        break;
    }
}
if (insertAfter < 0) { console.error('my-cat panel not found'); process.exit(1); }

var cameraModal = [
'',
'  <!-- Camera modal -->',
'  <div class="modal-overlay" id="cameraModal" style="display:none">',
'    <div class="modal" style="max-width:340px;padding:16px">',
'      <h3 style="text-align:center">📷 拍照</h3>',
'      <video id="camVideo" autoplay playsinline style="width:100%;height:240px;object-fit:cover;border-radius:12px;background:#000;margin:8px 0"></video>',
'      <canvas id="camCanvas" style="display:none"></canvas>',
'      <div id="camError" style="font-size:11px;color:#ff6b6b;text-align:center;margin:8px 0;display:none"></div>',
'      <div class="row">',
'        <button class="btn btn-secondary" onclick="closeCamera()">取消</button>',
'        <button class="btn btn-primary" onclick="snapPhoto()">📸 拍摄</button>',
'      </div>',
'    </div>',
'  </div>',
''
];

// Insert camera modal before 我的猫咪 section
lines.splice(insertAfter, 0, ...cameraModal);
html = lines.join('\n');

// 2. Replace pickPhoto/pickGallery with new camera functions
var oldPickFuncs = `function pickPhoto(){
  document.getElementById('cameraInput').click();
}
// Open gallery (no capture attribute → launches photo picker)
function pickGallery(){
  document.getElementById('galleryInput').click();
}`;

var newCameraFuncs = `function openCamera(){
  // Try getUserMedia on desktop, fallback to mobile capture
  var vid = document.getElementById('camVideo');
  var modal = document.getElementById('cameraModal');
  var errDiv = document.getElementById('camError');
  errDiv.style.display = 'none';
  modal.style.display = 'flex';

  if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
    navigator.mediaDevices.getUserMedia({video:{width:640,height:480,facingMode:'environment'}}).then(function(stream){
      vid.srcObject = stream;
      vid.play();
      window._camStream = stream;
    }).catch(function(e){
      // Try again with any camera
      navigator.mediaDevices.getUserMedia({video:true}).then(function(stream){
        vid.srcObject = stream;
        vid.play();
        window._camStream = stream;
      }).catch(function(e2){
        errDiv.style.display = 'block';
        errDiv.textContent = '无法打开摄像头，请确认已授权相机权限，或使用相册上传照片';
        // Fallback: try file input as last resort
        document.getElementById('galleryInput').click();
        modal.style.display = 'none';
      });
    });
  } else {
    errDiv.style.display = 'block';
    errDiv.textContent = '此浏览器不支持摄像头，请使用相册上传';
  }
}

function closeCamera(){
  var stream = window._camStream;
  if (stream) {
    stream.getTracks().forEach(function(t){t.stop();});
    window._camStream = null;
  }
  document.getElementById('camVideo').srcObject = null;
  document.getElementById('cameraModal').style.display = 'none';
}

function snapPhoto(){
  var vid = document.getElementById('camVideo');
  var canvas = document.getElementById('camCanvas');
  canvas.width = vid.videoWidth || 640;
  canvas.height = vid.videoHeight || 480;
  var ctx = canvas.getContext('2d');
  ctx.drawImage(vid, 0, 0, canvas.width, canvas.height);
  var dataUrl = canvas.toDataURL('image/jpeg', 0.85);
  document.getElementById('photoPreview').innerHTML = '<img src="' + dataUrl + '">';
  document.getElementById('analyzeBtn').disabled = false;
  closeCamera();
}`;

if (html.indexOf(oldPickFuncs) >= 0) {
    html = html.replace(oldPickFuncs, newCameraFuncs);
    console.log('pickPhoto/pickGallery replaced with openCamera/snapPhoto');
} else {
    console.log('WARNING: old pickPhoto functions not found, checking...');
    // Try the original version
    var altOldPick = `function pickPhoto(){
  var inp=document.getElementById('cameraInput');
  inp.click();
}
function pickGallery(){
  var inp=document.getElementById('galleryInput');
  inp.click();
}`;
    if (html.indexOf(altOldPick) >= 0) {
        html = html.replace(altOldPick, newCameraFuncs);
        console.log('pickPhoto/pickGallery replaced (alt version)');
    } else {
        // Try to find and replace pickPhoto function individually
        var pickStart = html.indexOf('function pickPhoto()');
        if (pickStart < 0) { console.error('pickPhoto not found at all'); process.exit(1); }
        // Find end of pickGallery function
        var galleryEnd = html.indexOf('function pickGallery');
        if (galleryEnd < 0) { console.error('pickGallery not found'); process.exit(1); }
        // Find closing } of pickGallery
        var braceCount = 0, end = galleryEnd;
        for (var i = end; i < html.length; i++) {
            if (html[i] === '{') braceCount++;
            else if (html[i] === '}') { braceCount--; if (braceCount === 0) { end = i; break; } }
        }
        html = html.slice(0, pickStart) + newCameraFuncs + html.slice(end + 1);
        console.log('pickPhoto/pickGallery replaced (manual find)');
    }
}

// 3. Remove old cameraInput.onchange reference
html = html.replace("document.getElementById('cameraInput').onchange=function(e){handlePhoto(e.target.files[0])};\n", '');

// 4. Update the gallery input bindings
if (html.indexOf("galleryInput').onchange") < 0) {
    // Make sure gallery input is bound
    var galleryLine = "document.getElementById('galleryInput').onchange=function(e){handlePhoto(e.target.files[0])};";
    if (html.indexOf(galleryLine) < 0) {
        // Insert before analyzeBtn.onclick
        var anchor = "document.getElementById('analyzeBtn').onclick=analyzeBreed;";
        if (html.indexOf(anchor) >= 0) {
            html = html.replace(anchor, galleryLine + '\n' + anchor);
            console.log('galleryInput binding added');
        }
    }
} else {
    console.log('galleryInput binding already exists');
}

fs.writeFileSync('cat-translator-v2.html', html);
console.log('Done: camera modal + openCamera/snapPhoto/closeCamera added');
