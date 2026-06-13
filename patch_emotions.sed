# Replace EMOTIONS array (lines 266-293) with Schötz 2024 calibrated values
266c\
// === Schötz 2024 calibrated emotion model (50 cats, 969 meows) ===\
// F0 data from Tables 3-4: Attention 559Hz±40, Content 495Hz±40,\
// Stressed 579Hz±39, Food 581Hz±38, Greeting 484Hz±44, Door 661Hz±30\
// Purr: Schötz 2015 F0=18-31Hz; Hiss: Yeon 2011 aperiodic ZCR>0.35\
var EMOTIONS = [\
  {id:'content',emoji:'😌',name:'满足/放松',desc:'咕噜声，舒服放松',\
   color:'#7ec8a0',f0Lo:15,f0Hi:60,dLo:1.5,dHi:25,zLo:0.001,zHi:0.05,cLo:20,cHi:200,\
   contour:'flat',harm:true,rep:false,e:'very-low',purr:true},\
  {id:'attention',emoji:'🐱',name:'寻求关注',desc:'打招呼或想引起注意',\
   color:'#f4a460',f0Lo:390,f0Hi:720,dLo:0.3,dHi:1.5,zLo:0.05,zHi:0.22,cLo:300,cHi:1100,\
   contour:'rising',harm:true,rep:false,e:'medium',purr:false},\
  {id:'hungry',emoji:'🍼',name:'饥饿/乞食',desc:'想吃东西了',\
   color:'#ff8c52',f0Lo:490,f0Hi:700,dLo:0.3,dHi:1.2,zLo:0.07,zHi:0.25,cLo:380,cHi:1200,\
   contour:'rising',harm:true,rep:true,e:'medium-high',purr:false},\
  {id:'pain',emoji:'😿',name:'痛苦/不适',desc:'不舒服或紧张',\
   color:'#ff6b6b',f0Lo:440,f0Hi:700,dLo:0.6,dHi:4,zLo:0.03,zHi:0.18,cLo:250,cHi:950,\
   contour:'falling',harm:true,rep:false,e:'high',purr:false},\
  {id:'angry',emoji:'😾',name:'愤怒/威胁',desc:'嘶嘶声，在警告',\
   color:'#ff4757',f0Lo:1200,f0Hi:8000,dLo:0.1,dHi:4,zLo:0.30,zHi:0.70,cLo:1800,cHi:6000,\
   contour:'flat',harm:false,rep:false,e:'medium',purr:false},\
  {id:'mating',emoji:'🚨',name:'发情/领地',desc:'发情期嚎叫或宣示领地',\
   color:'#b39dff',f0Lo:250,f0Hi:750,dLo:1.2,dHi:8,zLo:0.02,zHi:0.15,cLo:200,cHi:900,\
   contour:'flat',harm:true,rep:true,e:'high',purr:false}\
];
