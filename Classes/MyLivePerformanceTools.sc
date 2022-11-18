// =========================================================
// Title         : MyLivePerformanceTool
// Description   : personal live performance tool.
// Version       : 1.0
// =========================================================

MyLivePerformanceTool {
	classvar <>server;
	var src, analyses, indices, umapped, normed, tree, point, previous, play_slice, point, pen_tool, previous;

	*new {
		arg folder_path;
		server = server ? Server.default;
		^super.new.init(folder_path);
    }

	init {
		arg folder_path;

		fork {
			// load into a buffer
			var loader = FluidLoadFolder(folder_path).play(server,{"done loading folder".postln;});
			server.sync;
			"::::synced:::".postln;
			if(loader.buffer.numChannels > 1) {
				src = Buffer(server);
				loader.buffer.numChannels.do{
					arg chan_i;
					FluidBufCompose.processBlocking(server,
						loader.buffer,
						startChan:chan_i,
						numChans:1,
						gain:loader.buffer.numChannels.reciprocal,
						destination:src,
						destGain:1,
						action:{"copied channel: %".format(chan_i).postln}
					);
				};
			} {
				"loader buffer is already mono".postln;
				src = loader.buffer;
			};

			"init::done".postln;
			"start slicing...please wait".postln;
			this.processing();
		}
	}

	processing {
		this.slice();
	}

	slice {
    arg threshold = 0.05, metric = 9;

		indices = Buffer(server);
		FluidBufOnsetSlice.processBlocking(
			server,
			src,
			metric:metric, // FluCoMa algorithm.
			threshold:threshold, // how to determine onset point.
			indices:indices,
			action:{
				"found % slice points".format(indices.numFrames).postln;
				"average duration per slice: %".format(src.duration / (indices.numFrames+1)).postln;
				"slice::done".postln;
		});
	}

	analyze {
    arg numCoeffs = 13, startCoeff = 1;

		analyses = FluidDataSet(server);
		indices.loadToFloatArray(action:{
			arg fa;
			var mfccs = Buffer(server);
			var stats = Buffer(server);
			var flat = Buffer(server);

			fa.doAdjacentPairs{
				arg start, end, i;
				var num = end - start;

				// MFCC will analyze by using timbre infomation.
				// notice `startCoeff` start from 1, since 0 = average volume (we wanted only timbre factors).
				FluidBufMFCC.processBlocking(server,src,start,num,features:mfccs,numCoeffs:numCoeffs,startCoeff:startCoeff);
				FluidBufStats.processBlocking(server,mfccs,stats:stats,select:[\mean]);

				// flatten 13 layers into 13 frames (after select: [\mean]).
				// in others words, turn vertical array ([x][x][x][x]) to horizontal ([x,x,x,x]).
				FluidBufFlatten.processBlocking(server,stats,destination:flat);

				analyses.addPoint(i,flat);

				"analyzing slice % / %".format(i+1,fa.size-1).postln;

				// otherwise, it'll throw an error when many tasks are pushed to stack.
				if((i%100) == 99){
					server.sync;
					"::::synced:::".postln;
				}
			};

			server.sync;
			"::::synced:::".postln;

			analyses.print;
			"analyze::done".postln;
		});
	}

	map_dataset {
    arg numNeighbours=15, minDist=0.2;
    // minDist determines how close similar sample should be (large number less proximity)

		"start map_dataset...please wait".postln;
		umapped = FluidDataSet(server);
		// FluidUMAP turn 13 dimensional spaces into 2 dimensional spaces (for plotting in GUI later).
		FluidUMAP(server,numNeighbours:numNeighbours,minDist:minDist).fitTransform(
			analyses,
			umapped,
			action:{"map_dataset:::done".postln}
		);
	}

	normalize {
		normed = FluidDataSet(server);
		FluidNormalize(server).fitTransform(umapped,normed);
		"normalized::done".postln;
	}

	map_kd_tree {
		arg rad = 0.1;
		tree = FluidKDTree(server,radius: rad).fit(normed);
		"map_kd_tree::done".postln;
	}

	play_slice {
		arg index;
		{
			var startsamp = Index.kr(indices,index);
			var stopsamp = Index.kr(indices,index+1);
			var phs = Phasor.ar(0,BufRateScale.ir(src),startsamp,stopsamp);
			var sig = BufRd.ar(1,src,phs);
			var dursecs = (stopsamp - startsamp) / BufSampleRate.ir(src);
			var env;

			dursecs = min(dursecs,1);

			env = EnvGen.kr(Env([0,1,1,0],[0.03,dursecs-0.06,0.03]),doneAction:2);
			sig.dup * env;
		}.play;
	}

	plot {
		normed.dump({
			arg dict;
			point = Buffer.alloc(server,2);
			previous = nil;
			dict.postln;
			defer{
				FluidPlotter(dict:dict, mouseMoveAction:{
					arg view, x, y;
					point.setn(0,[x,y]);
					tree.kNearest(point,1,{
						arg nearest;
						if(nearest.isKindOf(Symbol) && (nearest != previous)){
							view.highlight_(nearest);
							this.play_slice(nearest.asInteger);
							previous = nearest;
						};
					});
				});
			}
		});
		"plot::done".postln;
	}

	listen {
		arg address = '/test_plotter/1', osc_def_name = \test_plotter_trigger, window=Rect(0,0,822,457.5);
		normed.dump({
			arg dict;
			point = Buffer.alloc(server,2);
			previous = nil;
			dict.postln;

			defer{
				FluidPlotter(bounds: window,dict:dict, mouseMoveAction:{
					arg view, mx,my;
					var penWidth=1;

					// [Bug] not working if no mouseEvent init.
					view.asParent.onClose = { this.stopListen() };

					"[muse_x,mouse_y]: % %".format([mx,my]).postln;

					OSCdef(osc_def_name, {|msg, time, addr, recvPort|

						var x = msg[1]; // normalized value (0..1) purposely for kNearest checking.
						var y = msg[2]; // normalized value (0..1)

						point.setn(0, [x,1 - y]);

						// QT GUI code must be schedule on the lower priority AppClock...
						{
							var canvas_x = x*view.asView.bounds.width; // scale to window bound (for drawing line).
							var canvas_y = y*view.asView.bounds.height;
							view.asView.drawFunc_({
								arg viewport;
								Pen.strokeColor = Color.black;
								Pen.width = penWidth;
								Pen.line(view.asPenTool.asPoint,canvas_x@canvas_y);
								view.asPenTool_([canvas_x,canvas_y]);
								Pen.stroke;
								view.drawHighlight(viewport);
							})
						}.defer;

						view.refresh;

						tree.kNearest(point,1,{
							arg nearest;
							if(nearest.isKindOf(Symbol) && (nearest != previous)){
								view.highlight_(nearest);
								this.play_slice(nearest.asInteger);
								previous = nearest;
							};
						});
					}, address);
				});
			}
		});

		"listen osc on port %".format(address).postln;
	}

	stopListen {
		arg osc_def_name = \test_plotter_trigger;
		"stop listening osc: %, port: %, address: %".format(osc_def_name, 57120, '/test_plotter/1').postln;
		OSCdef(osc_def_name).clear;
	}

}
