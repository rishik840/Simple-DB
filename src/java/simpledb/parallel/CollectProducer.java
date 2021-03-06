package simpledb.parallel;

import java.net.InetSocketAddress;
import java.util.ArrayList;

import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;

import simpledb.DbException;
import simpledb.DbIterator;
import simpledb.TransactionAbortedException;
import simpledb.Tuple;
import simpledb.TupleDesc;

/**
 * The producer part of the Collect Exchange operator.
 * 
 * The producer actively pushes the tuples generated by the child operator to
 * the paired CollectConsumer.
 * 
 * */
public class CollectProducer extends Producer {

    private static final long serialVersionUID = 1L;

    private transient WorkingThread runningThread;

    /**
     * The paired collect consumer address
     * */
    private final InetSocketAddress collectConsumerAddr;
    private DbIterator child;

    public String getName() {
        return "collect_p";
    }

    public CollectProducer(DbIterator child, ParallelOperatorID operatorID,
            String collectServerHost, int collectServerPort) {
        this(child, operatorID, new InetSocketAddress(collectServerHost,
                collectServerPort));
    }

    public CollectProducer(DbIterator child, ParallelOperatorID operatorID,
            InetSocketAddress collectServerAddr) {
        super(operatorID);
        this.child = child;
        this.collectConsumerAddr = collectServerAddr;
    }

    public InetSocketAddress getCollectServerAddr() {
        return this.collectConsumerAddr;
    }

    /**
     * The working thread, which executes the child operator and send the tuples
     * to the paired CollectConsumer operator
     * */
    class WorkingThread extends Thread {
        public void run() {

            IoSession session = ParallelUtility.createSession(
                    CollectProducer.this.collectConsumerAddr,
                    CollectProducer.this.getThisWorker().minaHandler, -1);

            try {
                ArrayList<Tuple> buffer = new ArrayList<Tuple>();
                long lastTime = System.currentTimeMillis();

                while (CollectProducer.this.child.hasNext()) {
                    Tuple tup = CollectProducer.this.child.next();
                    buffer.add(tup);
                    int cnt = buffer.size();
                    if (cnt >= TupleBag.MAX_SIZE) {
                        session.write(new TupleBag(
                                CollectProducer.this.operatorID,
                                CollectProducer.this.getThisWorker().workerID,
                                buffer.toArray(new Tuple[] {}),
                                CollectProducer.this.getTupleDesc()));
                        buffer.clear();
                        lastTime = System.currentTimeMillis();
                    }
                    if (cnt >= TupleBag.MIN_SIZE) {
                        long thisTime = System.currentTimeMillis();
                        if (thisTime - lastTime > TupleBag.MAX_MS) {
                            session.write(new TupleBag(
                                    CollectProducer.this.operatorID,
                                    CollectProducer.this.getThisWorker().workerID,
                                    buffer.toArray(new Tuple[] {}),
                                    CollectProducer.this.getTupleDesc()));
                            buffer.clear();
                            lastTime = thisTime;
                        }
                    }
                }
                if (buffer.size() > 0)
                    session.write(new TupleBag(CollectProducer.this.operatorID,
                            CollectProducer.this.getThisWorker().workerID,
                            buffer.toArray(new Tuple[] {}),
                            CollectProducer.this.getTupleDesc()));
                session.write(new TupleBag(CollectProducer.this.operatorID,
                        CollectProducer.this.getThisWorker().workerID)).addListener(new IoFutureListener<WriteFuture>(){

                            @Override
                            public void operationComplete(WriteFuture future) {
                                ParallelUtility.closeSession(future.getSession());
                            }});//.awaitUninterruptibly(); //wait until all the data have successfully transfered
            } catch (DbException e) {
                e.printStackTrace();
            } catch (TransactionAbortedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void open() throws DbException, TransactionAbortedException {
        this.child.open();
        this.runningThread = new WorkingThread();
        this.runningThread.start();
        super.open();
    }

    public void close() {
        super.close();
        child.close();
    }

    @Override
    public void rewind() throws DbException, TransactionAbortedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public TupleDesc getTupleDesc() {
        return this.child.getTupleDesc();
    }

    @Override
    protected Tuple fetchNext() throws DbException, TransactionAbortedException {
        try {
            // wait until the working thread terminate and return an empty tuple set
            runningThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public DbIterator[] getChildren() {
        return new DbIterator[] { this.child };
    }

    @Override
    public void setChildren(DbIterator[] children) {
        this.child = children[0];
    }

}
