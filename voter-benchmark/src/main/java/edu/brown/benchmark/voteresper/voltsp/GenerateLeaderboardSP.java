//package edu.brown.benchmark.voteresper.voltsp;

/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

//
// Accepts a vote, enforcing business logic: make sure the vote is for a valid
// contestant and that the voterdemosstore (phone number of the caller) is not above the
// number of allowed votes.
//

import org.voltdb.ProcInfo;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.types.TimestampType;

@ProcInfo (
	partitionInfo = "contestants_tbl.contestant_number:3",
    singlePartition = true
)
public class GenerateLeaderboardSP extends VoltProcedure {
	
	public static final int WIN_SLIDE = 10;
	public static final int WIN_SIZE = 100;
	public static final int VOTE_THRESHOLD = 20000;
	

    // Put the vote into the staging window
    public final SQLStmt insertVoteStagingStmt = new SQLStmt(
		"INSERT INTO w_staging (vote_id, phone_number, state, contestant_number, created, win_id) VALUES (?, ?, ?, ?, ?, ?);"
    );
    
    // Put the vote into the staging window
    public final SQLStmt insertVoteWindowDirectStmt = new SQLStmt(
		"INSERT INTO w_rows (vote_id, phone_number, state, contestant_number, created, win_id) VALUES (?, ?, ?, ?, ?, ?);"
    );
    
 // Find the number of rows in staging
    public final SQLStmt checkStagingCount = new SQLStmt(
		"SELECT cnt FROM staging_count WHERE row_id = 1;"
    );
       
 // Find the current window id
    public final SQLStmt checkCurrentVoteStmt = new SQLStmt(
		"SELECT win_id FROM current_win_id WHERE row_id = 1;"
    );
    
    public final SQLStmt checkNumVotesStmt = new SQLStmt(
		"SELECT cnt FROM votes_count WHERE row_id = 1;"
    );
    
    public final SQLStmt updateStagingCount = new SQLStmt(
    	"UPDATE staging_count SET cnt = ? WHERE row_id = 1;"
    );
    
    public final SQLStmt updateNumVotesStmt = new SQLStmt(
		"UPDATE votes_count SET cnt = ? WHERE row_id = 1;"
    );
    
    public final SQLStmt clearStagingCountStmt = new SQLStmt(
    	"UPDATE staging_count SET cnt = 0 WHERE row_id = 1;"
    );
    
    public final SQLStmt updateCurrentVoteStmt = new SQLStmt(
    	"UPDATE current_win_id SET win_id = ? WHERE row_id = 1;"
    );
    
 // Find the cutoff vote
    public final SQLStmt deleteCutoffVoteStmt = new SQLStmt(
		"DELETE FROM w_rows WHERE win_id <= ?;"
    );
    
    // Put the staging votes into the window
    public final SQLStmt insertVoteWindowStmt = new SQLStmt(
		"INSERT INTO w_rows (vote_id, phone_number, state, contestant_number, created, win_id) SELECT * FROM w_staging;"
    );
    
 // Pull aggregate from window
    public final SQLStmt deleteLeaderBoardStmt = new SQLStmt(
		"DELETE FROM leaderboard_tbl;"
    );
    
    // Pull aggregate from window
    public final SQLStmt updateLeaderBoardStmt = new SQLStmt(
		"INSERT INTO leaderboard_tbl (contestant_number, num_votes) SELECT contestant_number, count(*) FROM w_rows r JOIN contestants_tbl c ON c.contestant_number = r.contestant_number GROUP BY contestant_number;"
    );
    
 // Clear the staging window
    public final SQLStmt deleteStagingStmt = new SQLStmt(
		"DELETE FROM w_staging;"
    );
    
    public final SQLStmt getLowestContestant = new SQLStmt(
    		"SELECT contestant_number FROM v_votes_by_contestant ORDER BY num_votes ASC LIMIT 1;"
    );
	
    public VoltTable[] run(long voteId, long phoneNumber, String state, int contestantNumber, TimestampType timestamp) {
		
        voltQueueSQL(checkStagingCount);
        voltQueueSQL(checkCurrentVoteStmt);
        voltQueueSQL(checkNumVotesStmt);
        //voltQueueSQL(checkNumContestants);
        VoltTable validation[] = voltExecuteSQL();
	
        int stagingCount = (int)(validation[0].fetchRow(0).getLong(0)) + 1;
        long currentWinId = validation[1].fetchRow(0).getLong(0) + 1;
        int numVotes = (int)(validation[2].fetchRow(0).getLong(0)) + 1;
        //int numContestants = (int)(validation[4].fetchRow(0).getLong(0)) + 1; 
        
        if(currentWinId <= WIN_SIZE)
        {
        	voltQueueSQL(insertVoteWindowDirectStmt, voteId, phoneNumber, state, contestantNumber, timestamp, currentWinId);
        }
        else
        {
        	voltQueueSQL(insertVoteStagingStmt, voteId, phoneNumber, state, contestantNumber, timestamp, currentWinId);
        	voltQueueSQL(updateStagingCount, stagingCount);

	        if(stagingCount == WIN_SLIDE)
	        {
	        	//Check the window size and cutoff vote can be done one of two ways:
	        	//1) Two statements: one gets window size, one gets all rows to be deleted
	        	//2) Return full window to Java, and let it sort it out.  Better for large slides.
	        	//Likewise, either of these methods can be called in the earlier batch if that's better.
	        	
	        	long cutoffId = currentWinId - WIN_SIZE;
	            voltQueueSQL(deleteCutoffVoteStmt, cutoffId);
	            
	        	voltQueueSQL(insertVoteWindowStmt);
	    		voltQueueSQL(deleteLeaderBoardStmt);
	    		voltQueueSQL(updateLeaderBoardStmt);
	    		voltQueueSQL(deleteStagingStmt);
	    		voltQueueSQL(clearStagingCountStmt);
	    		//voltExecuteSQL(true);
	        }
    	}
        voltQueueSQL(updateCurrentVoteStmt, currentWinId);
        voltQueueSQL(updateNumVotesStmt, (numVotes % VOTE_THRESHOLD));
        
        voltExecuteSQL();
		
        // Set the return value to 0: successful vote
        if(numVotes == VOTE_THRESHOLD)
        {
        	voltQueueSQL(getLowestContestant);
        	validation = voltExecuteSQL();
        	if(validation[0].getRowCount() == 0)
        		return null;
        	else
        		return validation;
        }
        else
        {
        	return null;
        }
    }
}