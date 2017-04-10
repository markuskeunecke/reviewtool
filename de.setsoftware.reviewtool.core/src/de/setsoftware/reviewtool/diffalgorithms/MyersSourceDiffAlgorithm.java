package de.setsoftware.reviewtool.diffalgorithms;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.setsoftware.reviewtool.base.Pair;
import de.setsoftware.reviewtool.model.changestructure.ChangestructureFactory;
import de.setsoftware.reviewtool.model.changestructure.FileInRevision;
import de.setsoftware.reviewtool.model.changestructure.Fragment;

/**
 * Performs a standard line-based Myers diff and postprocesses the result to get better results for source files.
 */
class MyersSourceDiffAlgorithm implements IDiffAlgorithm {

    @Override
    public List<Pair<Fragment, Fragment>> determineDiff(FileInRevision fileOldInfo, byte[] fileOldContent,
            FileInRevision fileNewInfo, byte[] fileNewContent, String charset) throws IOException {

        final FullFileView<String> fileOld = this.toLines(fileOldContent, charset);
        final FullFileView<String> fileNew = this.toLines(fileNewContent, charset);
        final PathNode path = new MyersDiff().buildPath(fileOld, fileNew);

        this.postprocessPath(path, fileOld, fileNew);

        final List<Pair<Fragment, Fragment>> fragments =
                this.createFragmentsFromPath(path, fileOldInfo, fileOld, fileNewInfo, fileNew);
        Collections.reverse(fragments);
        return fragments;
    }

    private void postprocessPath(PathNode pathEnd, FullFileView<String> fileOld, FullFileView<String> fileNew) {
        //the algorithm generally has a bias to start diffs too far downwards, so we try to move diffs
        //  upwards to make them look better to the human eye
        this.makeBetterByMovingDiffsUpwards(pathEnd, fileNew);
        //the last diff can also be too far up due to stripping the common suffix, so check
        //  if it should be moved down
        this.makeBetterByMovingLastDiffDownwards(pathEnd, fileOld, fileNew);
    }

    private void makeBetterByMovingLastDiffDownwards(
            PathNode pathEnd, FullFileView<String> fileOld, FullFileView<String> fileNew) {
        if (!pathEnd.isSnake()) {
            //already ends with a diff => cannot move down
            return;
        }

        final int commonSuffixLength = pathEnd.getLengthOld();
        final PathNode lastDiff = pathEnd.getPrev();
        assert !lastDiff.isSnake();
        if (lastDiff.getLengthNew() == 0 && lastDiff.getLengthOld() == 0) {
            return;
        }
        int best = 0;
        for (int move = 1;
                move <= commonSuffixLength && this.canMoveDownwards(lastDiff, move, fileOld, fileNew);
                move++) {
            boolean isBetterStart;
            if (lastDiff.getLengthNew() > 0) {
                isBetterStart = this.isBetterStart(
                        fileNew.getItem(lastDiff.getStartPosNew() + best),
                        fileNew.getItem(lastDiff.getStartPosNew() + move));
            } else {
                isBetterStart = this.isBetterStart(
                        fileOld.getItem(lastDiff.getStartPosOld() + best),
                        fileOld.getItem(lastDiff.getStartPosOld() + move));
            }
            if (isBetterStart) {
                best = move;
            }
        }

        if (best != 0) {
            lastDiff.moveUpwards(-best);
        }
    }

    private boolean canMoveDownwards(
            PathNode cur, int stepsDownwards, FullFileView<String> fileOld, FullFileView<String> fileNew) {
        assert !cur.isSnake();
        final int newPosOld = cur.getPosOld() + stepsDownwards - 1;
        final int newPosNew = cur.getPosNew() + stepsDownwards - 1;
        //assumes that the caller has already checked that a move of this size is possible
        return fileOld.getItem(newPosOld).equals(fileOld.getItem(newPosOld - cur.getLengthOld()))
            && fileNew.getItem(newPosNew).equals(fileNew.getItem(newPosNew - cur.getLengthNew()));
    }


    private void makeBetterByMovingDiffsUpwards(PathNode pathEnd, FullFileView<String> fileNew) {
        PathNode cur = pathEnd;
        while (cur != null) {
            if (cur.isSnake()) {
                cur = cur.getPrev();
                continue;
            }

            int best = 0;
            for (int move = 1; this.canMoveUpwards(cur, move, fileNew); move++) {
                if (this.isBetterStart(cur, move, best, fileNew)
                        || this.willJoinTwoDiffs(cur, move)) {
                    best = move;
                }
            }

            if (best != 0) {
                cur.moveUpwards(best);
                if (cur.getPrev() != null && cur.getPrev().getLengthNew() == 0) {
                    //if there exists an empty snake now, remove it and join the two diff nodes
                    cur.joinWithNextDiff();
                } else {
                    cur = cur.getPrev();
                }
            } else {
                cur = cur.getPrev();
            }
        }
    }

    private boolean willJoinTwoDiffs(PathNode cur, int move) {
        return cur.getPrev() != null && move == cur.getPrev().getLengthNew();
    }

    private boolean canMoveUpwards(PathNode cur, int stepsUpwards, FullFileView<String> file) {
        assert !cur.isSnake();
        final int newPos = cur.getPosNew() - stepsUpwards;
        return cur.getLengthOld() == 0 //currently only additions are supported
            && newPos >= cur.getLengthNew()
            && (cur.getPrev() == null || newPos >= cur.getPrev().getStartPosNew())
            && file.getItem(newPos).equals(file.getItem(newPos - cur.getLengthNew()));
    }

    private boolean isBetterStart(PathNode cur, int move, int best, FullFileView<String> fileNew) {
        final String lineBest = fileNew.getItem(cur.getPosNew() - best);
        final String lineMove = fileNew.getItem(cur.getPosNew() - move);
        return this.isBetterStart(lineBest, lineMove);
    }

    private boolean isBetterStart(final String lineBest, final String lineMove) {
        final String lineBestTrim = lineBest.trim();
        final String lineMoveTrim = lineMove.trim();
        final boolean syntacticBetter =
                (lineMoveTrim.startsWith("/*") && !lineBestTrim.startsWith("/*"))
                || (!lineMoveTrim.startsWith("}") && lineBestTrim.startsWith("}"))
                || (!lineMoveTrim.startsWith("</") && lineBestTrim.startsWith("</"));
        return syntacticBetter;
    }

    private List<Pair<Fragment, Fragment>> createFragmentsFromPath(PathNode pathEnd,
            FileInRevision fileOldInfo, OneFileView<String> fileOld,
            FileInRevision fileNewInfo, OneFileView<String> fileNew) {

        final List<Pair<Fragment, Fragment>> ret = new ArrayList<>();
        PathNode cur = pathEnd;
        if (cur.isSnake()) {
            cur = cur.getPrev();
        }
        while (cur != null && cur.getPrev() != null && cur.getPrev().getPosNew() >= 0) {
            assert !cur.isSnake();

            final int endOld = cur.getPosOld();
            final int endNew = cur.getPosNew();

            cur = cur.getPrev();
            final int startOld = cur.getPosOld();
            final int startNew = cur.getPosNew();

            final Fragment original = ChangestructureFactory.createFragment(fileOldInfo,
                    ChangestructureFactory.createPositionInText(startOld + 1, 1),
                    ChangestructureFactory.createPositionInText(endOld + 1, 0),
                    this.joinRange(fileOld, startOld, endOld));
            final Fragment revised = ChangestructureFactory.createFragment(fileNewInfo,
                    ChangestructureFactory.createPositionInText(startNew + 1, 1),
                    ChangestructureFactory.createPositionInText(endNew + 1, 0),
                    this.joinRange(fileNew, startNew, endNew));
            final Pair<Fragment, Fragment> delta = Pair.create(original, revised);
            ret.add(delta);

            if (cur.isSnake()) {
                cur = cur.getPrev();
            }
        }
        return ret;
    }

    private FullFileView<String> toLines(byte[] contents, String charset) throws IOException {
        final BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(contents), charset));
        final List<String> lines = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            lines.add(line);
        }
        return new FullFileView<String>(lines.toArray(new String[lines.size()]));
    }

    private String joinRange(final OneFileView<String> file, final int fromIndex, final int to) {
        final StringBuilder ret = new StringBuilder();
        for (int i = fromIndex; i < to; i++) {
            ret.append(file.getItem(i)).append("\n");
        }
        return ret.toString();
    }
}