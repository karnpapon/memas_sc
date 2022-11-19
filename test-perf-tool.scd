a = MyLivePerformanceTool("/Users/karnpaponboonput/Desktop/samples/soundpharm/FoleyPerc - Loops/");
a = MyLivePerformanceTool("/Users/karnpaponboonput/Desktop/samples/soundpharm/test/");
// a = MyLivePerformanceTool(FluidFilesPath());

a.analyze;
a.map_dataset;

(
a.normalize;
a.map_kd_tree(0.01);
)

a.plot;

a.listen;
a.controller;
a.stopListen;


(
SynthDef("kick", {arg out = 0, amp = 1, sinfreq = 100, glissf = 0.1, att = 0.01, rel = 1, pan = 0;
	var env, snd, ramp;
	env = Env.perc(att, rel, amp).kr(doneAction: 2);
	ramp = XLine.kr(
		start: sinfreq,
		end: sinfreq * glissf,
		dur: rel
	);
	snd = SinOsc.ar(freq: ramp, mul: env);
	snd = Pan2.ar(snd, pan);
	Out.ar(out, snd);
}).add;

SynthDef("snare", {arg out = 0, amp = 0.1, sinfreq = 180, att = 0.01, rel = 0.2, ffreq = 2000, pan = 0;
	var env, snd1, snd2, sum;
	env = Env.perc(att, rel, amp).kr(doneAction: 2);
	snd1 = HPF.ar(
		in: WhiteNoise.ar,
		freq: ffreq,
		mul: env
	);
	snd2 = SinOsc.ar(freq: sinfreq, mul: env);
	sum = snd1 + snd2;
	Out.ar(out, Pan2.ar(sum, pan));
}).add;

SynthDef("sawSynth", { arg freq = 540, amp = 0.1, att = 0.1, rel = 2, lofreq = 1000, hifreq = 3000;
    var env, snd;
    env = Env.perc(
		attackTime: att,
		releaseTime: rel,
		level: amp
	).kr(doneAction: 2);
    snd = Saw.ar(freq: freq * [0.99, 1, 1.001, 1.008], mul: env);
	snd = LPF.ar(
		in: snd,
		freq: LFNoise2.kr(1).range(lofreq, hifreq)
	);
    snd = Splay.ar(snd);
    Out.ar(0, snd);
}).add;
)
)

Synth("kick");
Synth("snare");
Synth("sawSynth", [\freq, 330]);

(
OSCdef(\bounce_wall_y, {
	|msg, time, addr, recvPort|
	Synth("sawSynth", [\freq, [440,460,512,540,620].choose ]);
}, '/bounce_wall/y');


OSCdef(\bounce_wall_x, {
	|msg, time, addr, recvPort|
	Synth("sawSynth", [\freq, [240,360,562,511,489].choose]);
}, '/bounce_wall/x');
)


(
OSCdef(\bounce_wall_y).clear;
OSCdef(\bounce_wall_x).clear;
)